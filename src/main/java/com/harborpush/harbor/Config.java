package com.harborpush.harbor;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Config {
	private String mediaType;
	private Integer size;
	private String digest;
}
