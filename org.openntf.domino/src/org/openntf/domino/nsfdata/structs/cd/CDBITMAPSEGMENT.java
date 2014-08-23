package org.openntf.domino.nsfdata.structs.cd;

import java.nio.ByteBuffer;

import org.openntf.domino.nsfdata.structs.SIG;

/**
 * The bitmap data is divided into segments to optimize data storage within Domino. It is recommended that each segment be no larger than
 * 10k bytes. For best display speed, the segments sould be as large as possible, up to the practical 10k limit. A scanline must be
 * contained within a single segment, and cannot be divided between two segments. A bitmap must contain at least one segment, but may have
 * many segments. (editods.h)
 *
 */
public class CDBITMAPSEGMENT extends CDRecord {

	static {
		addFixedArray("Reserved", Integer.class, 2);
		addFixedUpgrade("ScanlineCount", Short.class);
		addFixedUpgrade("DataSize", Short.class);

		addVariableData("BitmapData", "getDataSize");
	}

	public CDBITMAPSEGMENT(final SIG signature, final ByteBuffer data) {
		super(signature, data);
	}

	/**
	 * Reserved for future use
	 */
	public int[] getReserved() {
		return (int[]) getStructElement("Reserved");
	}

	/**
	 * @return Number of compressed scanlines in seg
	 */
	public int getScanlineCount() {
		return (Integer) getStructElement("ScanlineCount");
	}

	/**
	 * @return Size, in bytes, of compressed data
	 */
	public int getDataSize() {
		return (Integer) getStructElement("DataSize");
	}

	// TODO uncompress the data (see docs)
	public byte[] getBitmapData() {
		return (byte[]) getStructElement("BitmapData");
	}
}
