package com.harborpush.harbor;

import com.google.common.collect.Lists;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * @author chenhui
 * @date 2022/8/22
 */
public class RetryIntercepter implements Interceptor {

	//最大重试次数
	public int maxRetry;

	//假如设置为3次重试的话，则最大可能请求4次（默认1次+3次重试）
	private int retryNum = 0;

	public RetryIntercepter(int maxRetry) {
		this.maxRetry = maxRetry;
	}

	@Override
	public Response intercept(Interceptor.Chain chain) throws IOException {
		Request request = chain.request();
		retryNum = 0;
		Response response = chain.proceed(request);
		while (Lists.newArrayList(502, 503, 504).contains(response.code())
				&& retryNum < maxRetry) {
			try {
				// 等待1S
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			retryNum++;
			System.out.println("retryNum=" + retryNum);
			response.close();
			response = chain.proceed(request);
		}
		return response;
	}
}