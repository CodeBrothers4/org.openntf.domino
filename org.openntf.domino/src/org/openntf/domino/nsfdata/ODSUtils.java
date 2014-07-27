package org.openntf.domino.nsfdata;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

public enum ODSUtils {
	;

	public static String fromLMBCS(final byte[] data) {
		return fromLMBCS(ByteBuffer.wrap(data));
	}
	public static String fromLMBCS(final ByteBuffer data) {
		Charset lmbcs = Charset.forName("x-lmbcs-1");
		CharBuffer buffer = lmbcs.decode(data);
		return buffer.toString();
	}
}
