package com.harborpush.harbor;

import lombok.Data;

@Data
public class Layer {

	private String mediaType;

	private Integer size;

	private String digest;
}