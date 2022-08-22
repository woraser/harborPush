package com.harborpush.harbor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Data
@Slf4j
@Service
public class HarborService {

	@Value("${harbor.tmp}")
	private String tmp;

	@Value("${harbor.remote}")
	private String remote;

	@Autowired
	private HarborHttpClient harborHttpClient;

	public String saveFile(MultipartFile file, String project) throws IOException {
		String path = this.tmp + "/" + project + "_" + System.currentTimeMillis() + "/" + file.getOriginalFilename();
		File targetFile = new File(path);
		boolean mkdirs = targetFile.mkdirs();
		if (mkdirs) {
			file.transferTo(new File(path));
		}
		return path;
	}

	public Response createProject(String project, String token, String csrf) throws HarborApiException {
		// public: true
		// 这里必须是true，否则会导致zun 拉取镜像失败
		String body = "{\"project_name\":\"" + project + "\",\"count_limit\":0,\"storage_limit\":-1,\"public\":true}";
		RequestBody requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body);
		try {
			Map<String, String> headers = new HashMap<>();
			headers.put("Authorization", "Bearer " + token);
			headers.put("X-Harbor-Csrf-Token", csrf);
			log.info("harbor-token: " + this.remote + "/api/v2.0/projects");
			return harborHttpClient.postOkHttp(this.remote + "/api/v2.0/projects", requestBody, headers);
		} catch (IOException e) {
			throw new HarborApiException("Create project fail." + project);
		}
	}

	public String digest(String repository, String version) throws HarborApiException {
		String url = String.format("%s/v2/%s/manifests/%s", remote, repository, version);
		try (Response response = harborHttpClient.getOkHttp(url)) {
			return response.header("Docker-Content-Digest");
		} catch (Exception e) {
			throw new HarborApiException(e.getMessage());
		}
	}

	private Map<String, String> createToken(String projectName) throws IOException, HarborApiException {
		Response initResponse = harborHttpClient.getOkHttp(this.remote + "/v2/_catalog");
		if (initResponse.code() != 200) {
			throw new HarborApiException("Refresh cookie fail.");
		}
		String cookie = initResponse.header("Set-Cookie");
		if (cookie != null && cookie.startsWith("sid=")) {
			harborHttpClient.setCookie(initResponse.header("Set-Cookie"));
		}

		String tokenUrl = String.format("%s/service/token?service=harbor-registry&scope=repository:%s:pull,push,delete", remote, projectName);
		Response tokenResponse = harborHttpClient.getOkHttp(tokenUrl);
		String csrf = tokenResponse.header("X-Harbor-Csrf-Token");
		if (StringUtils.isEmpty(csrf)) {
			log.error("There is no X-Harbor-Csrf-Token in Harbor token api headers.");
			throw new HarborApiException("There is no X-Harbor-Csrf-Token in Harbor token api headers.");
		}
		ResponseBody body = tokenResponse.body();
		if (body == null) {
			throw new HarborApiException("Harbor token api response is null.");
		}
		String bodyStr = body.string();
		JSONObject jsonObject = JSONObject.parseObject(bodyStr);
		String token = jsonObject.getString("token");
		if (StringUtils.isEmpty(token)) {
			log.error("There is no token of response in Harbor token api. response: {}", bodyStr);
			throw new HarborApiException("There is no token of response in Harbor token api.");
		}
		body.close();
		tokenResponse.close();
		initResponse.close();
		Map<String, String> map = new HashMap<>();
		map.put("token", token);
		map.put("csrf", csrf);
		map.put("cookie", tokenResponse.header("Set-Cookie"));
		return map;
	}

	public void delete(String projectName, String repository, String digest, String tag) throws HarborApiException, IOException {
		String url = String.format("%s/api/v2.0/projects/%s/repositories/%s/artifacts/%s/tags/%s", remote, projectName, repository, digest, tag);
		Map<String, String> token = createToken(projectName + "/" + repository);
		Response deleteResponse = harborHttpClient.deleteOkHttp(url, token.get("token"), token.get("csrf"), token.get("cookie"));
		if (deleteResponse.code() != 200) {
			ResponseBody body = deleteResponse.body();
			if (body != null) {
				log.error("Delete image api response {}", body.string());
			}
			throw new HarborApiException(String.format("Delete image fail in harbor. project: %s, repository: %s, digest: %s, tag: %s", projectName, repository, digest, tag));
		}

	}

	public String unArchiver(File sourceTar, String project) throws Exception {
		return TarUtil.doUnArchiver(sourceTar, tmp + "/" + project);
	}

	public JSONArray readManifest(String unTarPath) throws HarborApiException {
		String manifest = TarUtil.readJsonFile(unTarPath + File.separator + "manifest.json");
		JSONArray jsonArray = JSONObject.parseArray(manifest);
		if (Objects.isNull(jsonArray)) {
			String message = String.format("manifest convert error!path: %s,content: %s", unTarPath + File.separator + "manifest.json", manifest);
			throw new HarborApiException(message);
		}
		return jsonArray;
	}

	public ImageTagName push(JSONObject jsonObject, String project, String unTarPath) {
		try {
			Map<String, String> token = createToken(project);
			try (Response projectResponse = createProject(project, token.get("token"), token.get("csrf"))) {
				if (projectResponse.code() != 201 && projectResponse.code() != 409) {
					throw new HarborApiException(String.format("Create project error, %s", project));
				}
			}

			JSONArray repoTags = jsonObject.getJSONArray("RepoTags");
			ImageTagName ret = new ImageTagName();
			for (Object repoTag : repoTags) {
				String repo = repoTag.toString();
				String substring = repo.substring(repo.lastIndexOf('/') + 1);
				String[] split = substring.split(":");
				String imageName = split[0];
				String tag = split[1];
				log.info("Image name:{}, tag: {}", imageName, tag);
				ret.setName(imageName);
				ret.setTag(tag);
				JSONArray layers = jsonObject.getJSONArray("Layers");
				//1.上传layer
				log.info("STEP:1/3 PUSHING LAYERS STARTING...");

				List<Layer> layerList = new ArrayList<>();
				int i = 1;
				for (Object layer : layers) {
					String layerPath = unTarPath + File.separator + layer.toString();
					log.info("PUSHING LAYER:{}-{} >>>>", i, layers.size());
					Layer lay = pushLayer(project, layerPath, imageName);
					if (lay != null) {
						layerList.add(lay);
					}
					i++;
				}
				log.info("PUSHING LAYERS ENDED...");

				//2.上传config
				log.info("STEP:2/3 PUSHING CONFIG STARTING...");
				String config = jsonObject.getString("Config");
				String configPath = unTarPath + File.separator + config;
				pushingConfig(project, configPath, imageName);
				log.info("PUSHING CONFIG ENDED...");
				//3.上传manifest
				log.info("STEP:3/3 PUSHING MANIFEST STARTING...");
				pushingManifest(project, layerList, configPath, imageName, tag);
				log.info("PUSHING MANIFEST ENDED...");
				log.info("PUSHING {} COMPLETED!", repo);
			}
			return ret;
		} catch (Exception e) {
			log.error("Harbor push error.", e);
		}
		return null;
	}

	private Layer pushLayer(String project, String layerPath, String imageName) throws Exception {
		File layerFile = new File(layerPath);
		boolean layerExist = checkLayerExist(project, layerFile, imageName);
		if (layerExist) {
			log.info("LAYER ALREADY EXISTS! LAYER PATH: {}", layerPath);
			return null;
		}
		System.out.println(layerPath);
		String location = startingPush(project, imageName);
		log.info("location: {}", location);
		chunkPush(layerFile, location);
		Layer layer = new Layer();
		String hash2561 = TarUtil.hash256(layerFile);
		layer.setDigest("sha256:" + hash2561);
		layer.setMediaType("application/vnd.docker.image.rootfs.diff.tar");
		layer.setSize((int) layerFile.length());
		return layer;
	}

	private boolean checkLayerExist(String project, File layer, String imageName) throws Exception {
		String hash256 = TarUtil.hash256(layer);
		String url = String.format("%s/v2/%s/blobs/%s", remote, project + "/" + imageName, "sha256:" + hash256);
		try (Response response = harborHttpClient.headOkHttp(url)) {
			return response.code() == 200;
		} catch (Exception e) {
			log.error("checkLayerExist ", e);
		}
		return false;
	}

	private String startingPush(String project, String imageName) {
		String url = String.format("%s/v2/%s/blobs/uploads/", remote, project + "/" + imageName);
		try (Response response = harborHttpClient.postOkHttp(url, RequestBody.create(null, ""))) {
			log.info("response code: {}", response.code());
			if (response.code() == 202) {
				return response.header("location");
			}
		} catch (Exception e) {
			log.error("checkLayerExist ", e);
		}
		return "";
	}

	private void chunkPush(File layerFile, String url) throws Exception {
		long length = layerFile.length();
		int len = 1024 * 1024 * 10;
		byte[] chunk = new byte[len];
		int offset = 0;
		int index = 0;
		MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
		while (true) {
			log.info("offset: {}", offset);
			log.info("length: {}", length);
			byte[] blocks;
			if (offset < length) {
				blocks = TarUtil.getBlock(offset, layerFile, chunk.length);
			} else {
				// https://docs.docker.com/registry/spec/api/#pushing-an-image
				blocks = new byte[0];
			}

			if (Objects.isNull(blocks)) {
				break;
			}
			offset += blocks.length;
			messageDigest.update(blocks);
			log.info(String.format("pushing range:[%s-%s]... %s", index, offset, String.format("%.2f", (float) offset / (float) length * 100)));
			if (offset >= length && blocks.length == 0) {
				log.info("offset >= length && blocks.length == 0");
				int totalLength = new Long(length).intValue();
				String hash256 = TarUtil.byte2Hex(messageDigest.digest());
				url = String.format("%s&digest=sha256:%s", url, hash256);
				log.info("put url: {}", url);
				try (Response response = harborHttpClient.putOkHttp(url, totalLength, totalLength, blocks)) {
					printResponse(response, url);
					if (response.code() != 201) {
						throw new HarborApiException(String.format("Chunk push error! code: %s, digest: %s, %s", response.code(), hash256, response.body()));
					}
				}
				break;
			} else {
				log.info("not offset >= length && blocks.length == 0");
				log.info("patchOkHttp url: {}", url);
				Response response = harborHttpClient.patchOkHttp(url, index, offset, blocks);
				printResponse(response, url);
				if (response.code() != 202) {
					throw new HarborApiException(String.format("patch error!code:%s,response:%s", response.code(), response.body()));
				}
				url = response.header("location");
			}
			index = offset;
		}
	}

	private void printResponse(Response response, String url) {
		ResponseBody body = response.body();
		try {
			if (body == null) {
				log.info("chunkPush response body is null");
			} else {
				byte[] bytes = body.bytes();
				String bodyStr = new String(bytes);
				log.info(url);
				log.info("chunkPush response, response code: {}, body: {}", response.code(), bodyStr);
			}
		} catch (IOException e) {
			log.info("chunkPush response get body bytes error", e);
		}
	}

	private void monolithicPush(File layer, String url) throws Exception {
		byte[] contents = FileUtils.readFileToByteArray(layer);
		String hash256 = TarUtil.hash256(layer);
		url = url + "&digest=sha256:" + hash256;
		try (Response response = harborHttpClient.putOkHttp(url, contents)) {
			if (response.code() != 201) {
				log.error("monolithicPush error!code:{},{}", response.code(), response.body());
				throw new RuntimeException("monolithicPush error!");
			}
		} catch (Exception ignored) {

		}
	}

	private void pushingConfig(String project, String configPath, String imageName) throws Exception {
		File file = new File(configPath);
		if (checkLayerExist(project, file, imageName)) {
			log.info("{} exists!", configPath);
			return;
		}
		log.info("start pushing config...");
		String url = startingPush(project, imageName);
		monolithicPush(file, url);
		log.info(String.format("config:%s upload success!", configPath));
	}

	private void pushingManifest(String project, List<Layer> layerList, String configPath, String imageName, String tag) throws Exception {
		ManifestV2 manifestV2 = new ManifestV2()
				.setMediaType("application/vnd.docker.distribution.manifest.v2+json")
				.setSchemaVersion(2);
		File configFile = new File(configPath);
		String hash256 = TarUtil.hash256(configFile);
		Config config = new Config()
				.setMediaType("application/vnd.docker.container.image.v1+json")
				.setDigest("sha256:" + hash256)
				.setSize((int) configFile.length());
		manifestV2.setConfig(config);
		manifestV2.setLayers(layerList);
		String manifestStr = JSON.toJSONString(manifestV2);
		String url = String.format("%s/v2/%s/manifests/%s", remote, project + "/" + imageName, tag);
		try (Response response = harborHttpClient.putManifestOkHttp(url, manifestStr.getBytes(StandardCharsets.UTF_8))) {
			if (response.code() != 201) {
				log.error("upload manifest error!,code: {},response: {}", response.code(), response.body());
			}
		} catch (Exception e) {
			log.error("Harbor pushingManifest error.", e);
		}
		log.info("manifest upload success!");
	}

}
