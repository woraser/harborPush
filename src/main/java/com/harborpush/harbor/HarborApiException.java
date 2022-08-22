package com.harborpush.harbor;

import lombok.Data;

@Data
public class HarborApiException extends Exception {
	private String message;

	public HarborApiException(String message) {
		super(message);
	}
}
