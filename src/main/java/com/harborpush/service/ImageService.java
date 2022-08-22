package com.harborpush.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * @author chenhui
 * @date 2022/8/22
 */
public interface ImageService {

	/**
	 * upload images
	 *
	 * @param file MultipartFile
	 * @author Charles.chen
	 * @date 2022/8/22
	 */
	void importImage(MultipartFile file);
}
