package com.harborpush.harbor;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class ManifestV2 {

	private Integer schemaVersion;

	private String mediaType;

	private Config config;

	private List<Layer> layers;
}
