package com.harborpush.controller;

import com.harborpush.service.ImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author chenhui
 * @date 2022/8/22
 */
@RestController
@RequestMapping("/api/file/")
@Slf4j
public class HarborImageController {

	@Autowired
	private ImageService imageService;


	@PostMapping(value = "/import")
	public void importImage(@RequestParam("ImageFile") MultipartFile file) {
		imageService.importImage(file);
	}

}
