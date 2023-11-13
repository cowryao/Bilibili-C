package com.imooc.bilibili.service.util;

import com.github.tobato.fastdfs.domain.fdfs.FileInfo;
import com.github.tobato.fastdfs.domain.fdfs.MetaData;
import com.github.tobato.fastdfs.domain.fdfs.StorePath;
import com.github.tobato.fastdfs.service.AppendFileStorageClient;
import com.github.tobato.fastdfs.service.FastFileStorageClient;
import com.imooc.bilibili.dao.FileDao;
import com.imooc.bilibili.domain.exception.ConditionException;
import com.mysql.cj.util.StringUtils;
import io.netty.util.internal.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

public class FastDFSUtil {

    @Autowired
    private FastFileStorageClient fastFileStorageClient;

    @Autowired
    private AppendFileStorageClient appendFileStorageClient;

    @Autowired
    private RedisTemplate<String,String> redisTemplate;

    @Autowired
    private FileDao fileDao;

    private static final String DEFAULT_GROUP = "group1";

    private static final String PATH_KEY = "path-key:";

    private static final String UPLOADED_SIZE_KEY = "uploaded-size-key:";

    private static final String UPLOADED_NO_KEY = "uploaded-no-key:";

    private static final int SLICE_SIZE = 1024 * 1024 * 2;

    public String getFileType(MultipartFile file){
        if(file == null){
            throw new ConditionException("非法文件");
        }
        String fileName = file.getOriginalFilename();
        int index = fileName.lastIndexOf(".");
        return fileName.substring(index+1);
    }
    //上传
    public String uploadCommonFile(MultipartFile file) throws IOException {
        Set<MetaData> metaDataSet = new HashSet<>();
        String fileType = this.getFileType(file);
        StorePath storePath = fastFileStorageClient.uploadFile(file.getInputStream(),file.getSize(),fileType,metaDataSet);
        return storePath.getFullPath();

    }

    //上传可以断点续传的文件
    public String uploadAppenderFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String fileType = this.getFileType(file);
        StorePath storePath = appendFileStorageClient.uploadAppenderFile(DEFAULT_GROUP,file.getInputStream(),file.getSize(),fileType);
        return storePath.getPath();
    }

    public void modifyAppenderFile(MultipartFile file,String filePath,long offset) throws IOException {
        appendFileStorageClient.modifyFile(DEFAULT_GROUP,filePath,file.getInputStream(),file.getSize(),offset);
    }



    public String uploadFileBySlices(MultipartFile file, String fileMD5, Integer sliceNo,Integer totalSliceNo) throws IOException {
        if(file == null || sliceNo == null || totalSliceNo == null){
            throw new ConditionException("参数异常");
        }
        String pathKey = PATH_KEY + fileMD5;
        String uploadedSizeKey = UPLOADED_SIZE_KEY + fileMD5;
        String uploadedNoKey = UPLOADED_NO_KEY + fileMD5;
        String uploadedSizeStr = redisTemplate.opsForValue().get(uploadedSizeKey);
        long uploadedSize = 0L;
        if(!StringUtils.isNullOrEmpty(uploadedSizeStr)){
            uploadedSize = Long.valueOf(uploadedSizeStr);
        }
        String fileType = this.getFileType(file);
        if(sliceNo == 1){//上传的是第一个分片
            String path = this.uploadAppenderFile(file);
            if(StringUtil.isNullOrEmpty(path)){
                throw new ConditionException("上传失败");
            }
            redisTemplate.opsForValue().set(pathKey,path);
            redisTemplate.opsForValue().set(uploadedNoKey,"1");
        }else{
            String filePath = redisTemplate.opsForValue().get(pathKey);
            if(StringUtil.isNullOrEmpty(filePath)){
                throw new ConditionException("上传失败");
            }
            this.modifyAppenderFile(file,filePath,uploadedSize);
            redisTemplate.opsForValue().increment(uploadedNoKey);
        }
        //修改历史上传分片文件大小
        uploadedSize += file.getSize();
        redisTemplate.opsForValue().set(uploadedSizeKey,String.valueOf(uploadedSize));
        //如果所有分片全部上传完毕，删除redis中的key和value
        String uploadedNoStr = redisTemplate.opsForValue().get(uploadedNoKey);
        Integer uploadedNo = Integer.valueOf(uploadedNoStr);
        String resultPath = "";
        if(uploadedNo.equals(totalSliceNo)){
            resultPath = redisTemplate.opsForValue().get(pathKey);
            List<String> keyList = Arrays.asList(uploadedNoKey,pathKey,uploadedSizeKey);
            redisTemplate.delete(keyList);
        }
        return resultPath;
    }

    public void convertFileToSlices(MultipartFile multipartFile) throws IOException {
        String fileName = multipartFile.getOriginalFilename();
        String fileType = this.getFileType(multipartFile);
        File file = this.multipartFileToFile(multipartFile);
        long fileLength = file.length();
        int count = 1;
        for(int i = 0;i<fileLength;i += SLICE_SIZE){
            RandomAccessFile randomAccessFile = new RandomAccessFile(file,"r");
            randomAccessFile.seek(i);
            byte[] bytes = new byte[SLICE_SIZE];
            int len = randomAccessFile.read(bytes);
            String path = "/users/hat/tmpfile/"+count+"."+fileType;
            File slice = new File(path);
            FileOutputStream fos = new FileOutputStream(slice);
            fos.write(bytes,0,len);
            fos.close();
            randomAccessFile.close();
            count++;
        }
        file.delete();
    }

    public File multipartFileToFile(MultipartFile multipartFile) throws IOException {
        String originalFilename = multipartFile.getOriginalFilename();
        String[] fileName = originalFilename.split("\\.");
        File file = File.createTempFile(fileName[1],"."+fileName[1]);
        multipartFile.transferTo(file);
        return file;
    }



    //删除
    public void deleteFile(String filePath){
        fastFileStorageClient.deleteFile(filePath);
    }

    @Value("${fdfs.http.storage-addr}")
    private String httpFdfsStorageAddr;
    public void viewVideoOnlineBySlices(HttpServletRequest request,
                                        HttpServletResponse response,
                                        String path) throws Exception {
        FileInfo fileInfo = fastFileStorageClient.queryFileInfo(DEFAULT_GROUP,path);
        long totalFileSize = fileInfo.getFileSize();
        String url = httpFdfsStorageAddr + path;
        Enumeration<String> headerNames = request.getHeaderNames();
        Map<String,Object> headers = new HashMap<>();
        while(headerNames.hasMoreElements()){
            String header = headerNames.nextElement();
            headers.put(header,request.getHeader(header));
        }
        String rangeStr = request.getHeader("Range");
        String[] range;
        if(StringUtil.isNullOrEmpty(rangeStr)){
            rangeStr = "bytes=0-"+(totalFileSize-1);
        }
        range = rangeStr.split("bytes=|-");
        long begin = 0;
        if(range.length >= 2){
            begin = Long.parseLong(range[1]);
        }
        long end = totalFileSize-1;
        if(range.length >= 3){
            end = Long.parseLong(range[2]);
        }
        long len = end - begin + 1;
        String contentRange = "bytes "+begin+"-"+end+"/"+totalFileSize;
        response.setHeader("Content-Range",contentRange);
        response.setHeader("Accept-Ranges","bytes");
        response.setHeader("Content-Type","video/mp4");
        response.setContentLength((int)len);
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        HttpUtil.get(url,headers,response);
    }

    //下载


}
