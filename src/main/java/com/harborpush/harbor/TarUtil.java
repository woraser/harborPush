package com.harborpush.harbor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
public class TarUtil {

	public static String doUnArchiver(File sourceFile, String destPath)
			throws Exception {
		try (FileInputStream fis = new FileInputStream(sourceFile)) {
			try (BufferedInputStream bis = new BufferedInputStream(fis)) {
				try (TarArchiveInputStream tais = new TarArchiveInputStream(bis)) {
					TarArchiveEntry tae = null;
					byte[] buf = new byte[1024];
					destPath = createTempDirIfNotExist(sourceFile.getName(), destPath);
					while ((tae = tais.getNextTarEntry()) != null) {
						File f = new File(destPath + "/" + tae.getName());
						if (tae.isDirectory()) {
							f.mkdirs();
						} else {
							/*
							 * 父目录不存在则创建
							 */
							File parent = f.getParentFile();
							if (!parent.exists()) {
								parent.mkdirs();
							}

							FileOutputStream fos = new FileOutputStream(f);
							try (BufferedOutputStream bos = new BufferedOutputStream(fos)) {
								int len;
								while ((len = tais.read(buf)) != -1) {
									bos.write(buf, 0, len);
								}
								bos.flush();
							}
						}
					}
					return destPath;
				}
			}
		}
	}

	private static synchronized String createTempDirIfNotExist(String pathName, String basePath) {
		String dir;
		if (pathName.contains(".")) {
			String[] split = pathName.split("\\.");
			dir = basePath + File.separator + split[0];
		} else {
			dir = basePath + File.separator + pathName;
		}
		File file = new File(dir);
		if (!file.exists()) {
			file.mkdirs();
		}
		return dir;
	}

	public static String readJsonFile(String fileName) {
		try {
			File jsonFile = new File(fileName);
			Reader reader = new InputStreamReader(new FileInputStream(jsonFile), StandardCharsets.UTF_8);
			return IOUtils.toString(reader);
		} catch (IOException e) {
			log.error("TarUtil readJsonFile error.", e);
		}
		return null;
	}

	public static String hash256(File file) {

		try (InputStream fis = new FileInputStream(file)) {
			byte[] buffer = new byte[4096];
			MessageDigest md5 = MessageDigest.getInstance("SHA-256");
			for (int numRead = 0; (numRead = fis.read(buffer)) > 0; ) {
				md5.update(buffer, 0, numRead);
			}
			return byte2Hex(md5.digest());
		} catch (Exception e) {
			log.error("TarUtil hash256 error.", e);
		}
		return "";
	}

	public static String byte2Hex(byte[] bytes) {
		StringBuilder stringBuffer = new StringBuilder();
		String temp;
		for (byte b : bytes) {
			temp = Integer.toHexString(b & 0xFF);
			if (temp.length() == 1) {
				// 1得到一位的进行补0操作
				stringBuffer.append("0");
			}
			stringBuffer.append(temp);
		}
		return stringBuffer.toString();
	}

	public static byte[] getBlock(long offset, File file, int blockSize) {
		byte[] result = new byte[blockSize];
		try (RandomAccessFile accessFile = new RandomAccessFile(file, "r")) {
			accessFile.seek(offset);
			int readSize = accessFile.read(result);
			if (readSize == -1) {
				return null;
			} else if (readSize == blockSize) {
				return result;
			} else {
				byte[] tmpByte = new byte[readSize];
				System.arraycopy(result, 0, tmpByte, 0, readSize);
				return tmpByte;
			}
		} catch (IOException e) {
			log.error("TarUtil getBlock error.", e);
		}
		return null;
	}
}
