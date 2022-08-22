package com.harborpush.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.harborpush.harbor.HarborService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

/**
 * @author chenhui
 * @date 2022/8/22
 */
@Slf4j
@Service("imageServiceImpl")
public class ImageServiceImpl implements ImageService {

	private final static String PROJECT_NAME = "test-project";

	@Autowired
	private HarborService harborService;

	@Override
	public void importImage(MultipartFile file) {
		pushImageFile(file);
	}

	private void pushImageFile(MultipartFile imageFile) {
		String originalFilename = imageFile.getOriginalFilename();
		if (originalFilename == null || !originalFilename.endsWith(".tar")) {
			log.error("Invalid upload file");
			return;
		}
		String path = null;
		try {
			// 转存tar 文件
			path = harborService.saveFile(imageFile, PROJECT_NAME);
			File sourceTar = new File(path);
			String[] paths = path.split("/");
			// 解压文件
			String unTarPath = harborService.unArchiver(sourceTar, paths[paths.length - 2]);
			JSONArray jsonArray = harborService.readManifest(unTarPath);
			if (jsonArray == null || jsonArray.size() != 1) {
				log.error("Image tar file invalid, there is more than one tag");
				return;
			}
			// 及时清理初始文件
			if (sourceTar.exists()) {
				boolean delete = sourceTar.delete();
				if (!delete) {
					log.warn("Delete image tar file failed, path: {}", sourceTar.getAbsolutePath());
				}
			}

			JSONObject jsonObject = jsonArray.getJSONObject(0);
			String repoTags = jsonObject.getString("RepoTags");
			if (StringUtils.isBlank(repoTags)) {
				log.error("Image tar tag invalid, there is no tag info: {}", JSONObject.toJSON(jsonObject));
				return;
			}
			// 获取镜像基本信息，名称/版本/sha256等
			harborService.push(jsonObject, PROJECT_NAME, unTarPath);

		} catch (Exception e) {
			log.error("Import image file error", e);
		} finally {
			if (!StringUtils.isBlank(path)) {
				clearTmpFile(path);
			}
		}
	}

	private void clearTmpFile(String path) {
		String folder = path.substring(0, path.lastIndexOf("."));
		File tarFile = new File(path);
		String uploadTmp = folder.substring(0, folder.lastIndexOf("/"));
		File folderDir = new File(uploadTmp);
		if (tarFile.exists()) {
			boolean delete = tarFile.delete();
			if (!delete) {
				log.warn("Delete image tar file failed, path: {}", tarFile.getAbsolutePath());
			}
		}
		if (folderDir.exists()) {
			try {
				FileUtils.deleteDirectory(folderDir);
			} catch (IOException e) {
				log.warn("Delete image user folder failed, path: " + folderDir.getAbsolutePath(), e);
			}
		}
	}
}
