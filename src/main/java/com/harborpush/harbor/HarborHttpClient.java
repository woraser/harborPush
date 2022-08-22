package com.harborpush.harbor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
@Data
@Service
@Scope("prototype")
public class HarborHttpClient implements InitializingBean {

	private final TrustAllManager trustAllManager = new TrustAllManager();
	@Value("${harbor.user}")
	private String user;
	@Value("${harbor.password}")
	private String password;
	@Value("${harbor.remote}")
	private String remote;

	private OkHttpClient okHttpClient;

	private String cookie;

	@Override
	public void afterPropertiesSet() {
		okHttpClient = new OkHttpClient.Builder()
				.authenticator((route, response) -> {
					String credential = Credentials.basic(user, password, StandardCharsets.UTF_8);
					return response.request().newBuilder().header("Authorization", credential).build();
				})
				.addInterceptor(new RetryIntercepter(10))
				.connectTimeout(60, TimeUnit.MINUTES)
				.readTimeout(60, TimeUnit.MINUTES)
				.sslSocketFactory(createTrustAllSSLFactory(), trustAllManager)
				.writeTimeout(60, TimeUnit.MINUTES)
				.hostnameVerifier((h, s) -> true)
				.build();
		Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
	}

	protected SSLSocketFactory createTrustAllSSLFactory() {
		SSLSocketFactory ssfFactory = null;
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());
			ssfFactory = sc.getSocketFactory();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return ssfFactory;
	}


	public Response headOkHttp(String url) throws IOException {
		Request request = new Request.Builder()
				.url(url)
				.head()
				.build();
		return okHttpClient.newCall(request).execute();
	}

	public Response getOkHttp(String url) {
		try {
			log.info("Harbor getToken url: {}", url);
			Request.Builder builder = new Request.Builder()
					.url(url)
					.header("Accept", "application/vnd.docker.distribution.manifest.v2+json");
			if (!StringUtils.isEmpty(this.cookie)) {
				builder.header("Cookie", cookie);
			}
			Request request = builder.build();
			return okHttpClient.newCall(request).execute();
		} catch (Exception e) {
			log.error("Harbor getToken error, ", e);
		}
		return null;
	}

	public Response deleteOkHttp(String url, String token, String csrf, String cookie) throws IOException {
		this.cookie = cookie;
		Request request = new Request.Builder()
				.url(url)
				.header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
				.header("Authorization", "Bearer " + token)
				.header("X-Harbor-Csrf-Token", csrf)
				.header("Cookie", cookie)
				.delete()
				.build();
		return okHttpClient.newCall(request).execute();
	}

	public Response postOkHttp(String url, RequestBody body)
			throws IOException {
		Request request = new Request.Builder()
				.url(url)
				.post(body)
				.build();
		return okHttpClient.newCall(request).execute();
	}


	public Response postOkHttp(String url, RequestBody body, Map<String, String> headers)
			throws IOException {
		Request.Builder builder = new Request.Builder().url(url);
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			builder.header(key, value);
		}
		Request request = builder.post(body).build();
		return okHttpClient.newCall(request).execute();
	}

	public Response patchOkHttp(String url, int index, int offset, byte[] buffer)
			throws IOException {
		MediaType mediaType = MediaType.parse("application/octet-stream");
		RequestBody body = RequestBody.create(mediaType, buffer);
		Request request = new Request.Builder()
				.url(url)
				.patch(body)
				.header("Content-Type", "application/octet-stream")
				.header("Content-Length", String.valueOf(buffer.length))
				.header("Content-Range", String.format("%s-%s", index, offset))
				.build();
		return okHttpClient.newCall(request).execute();
	}

	public Response putOkHttp(String url, int index, int offset, byte[] buffer)
			throws IOException {
		MediaType mediaType = MediaType.parse("application/octet-stream");
		RequestBody body = RequestBody.create(mediaType, buffer);
		Request request = new Request.Builder()
				.url(url)
				.put(body)
				.header("Content-Type", "application/octet-stream")
				.header("Content-Length", String.valueOf(buffer.length))
				.header("Content-Range", String.format("%s-%s", index, offset))
				.build();
		return okHttpClient.newCall(request).execute();
	}

	public Response putOkHttp(String url, byte[] buffer)
			throws IOException {
		MediaType mediaType = MediaType.parse("application/octet-stream");
		RequestBody body = RequestBody.create(mediaType, buffer);
		Request request = new Request.Builder()
				.url(url)
				.put(body)
				.header("Content-Type", "application/octet-stream")
				.header("Content-Length", String.valueOf(buffer.length))
				.build();
		return okHttpClient.newCall(request).execute();
	}

	public Response putManifestOkHttp(String url, byte[] buffers)
			throws IOException {
		MediaType mediaType = MediaType.parse("application/vnd.docker.distribution.manifest.v2+json");
		RequestBody body = RequestBody.create(mediaType, buffers);
		Request request = new Request.Builder()
				.url(url)
				.put(body)
				.header("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
				.build();
		return okHttpClient.newCall(request).execute();
	}

}

class TrustAllManager implements X509TrustManager {
	@Override
	public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {

	}

	@Override
	public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {

	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return new X509Certificate[0];
	}
}
