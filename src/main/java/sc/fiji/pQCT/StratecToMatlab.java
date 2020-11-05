/*
BSD 2-Clause License

Copyright (c) 2018, Timo Rantalainen
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package sc.fiji.pQCT;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

//TYP file reading
import java.io.InputStream;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;

// This file format is supported in SCIFIO already,
// but we'll keep this plugin around for people who don't have it enabled.
// Also SCIFIO is as of yet experimental code.
// TODO Remove when ImageJ/Fiji comes with SCIFIO enabled by default
public class StratecToMatlab {

	private static final int HEADER_LENGTH = 1609;
	private String PatName;
	private long PatNo;
	private int PatMeasNo;
	private long PatBirth;
	private long MeasDate;
	private double VoxelSize;
	private int PicX0;
	private int PicY0;
	private int PicMatrixX;
	private int PicMatrixY;
	private String MeasInfo;
	private String Device;
	private String PatID;
	private double ObjLen;
	private String fileName;
	private String properties;
	private int[] pixels = null;
	
	public int[] getPixels(){
		return pixels;
	}
	
	public int[] getSize(){
		return new int[]{PicMatrixX,PicMatrixY};
	}
	
	
	public StratecToMatlab(String fileIn){
		readFile(fileIn);
	}


	private static String getNByteString(final ByteBuffer buffer, final int pos) {
		buffer.position(pos);
		final byte n = buffer.get();
		final byte[] bytes = new byte[n];
		buffer.get(bytes);
		return new String(bytes);
	}

	private void readFile(final String path) {
		final File file = new File(path);
		final int bytes = (int) file.length();
		if (bytes < HEADER_LENGTH) {
			System.out.println("Reading the Stratec file failed: File length < 1609 bytes.");
			return;
		}
		try (final DataInputStream dataInputStream = new DataInputStream(
			new BufferedInputStream(new FileInputStream(file))))
		{
			// Allocate memory for reading the file into memory
			final byte[] data = new byte[bytes];
			// Read the data to memory
			dataInputStream.read(data, 0, bytes);
			final ByteBuffer buffer = ByteBuffer.wrap(data).order(
				ByteOrder.LITTLE_ENDIAN);
			readHeader(buffer);
			readImage(buffer);
		}
		catch (final IOException e) {
			System.out.println("Reading the Stratec file failed: " + e.getMessage());
		}
	}

	private void readHeader(final ByteBuffer buffer)
	{
		Device = getNByteString(buffer, 1050);
		if (!Device.toLowerCase().contains(".typ")) {
			System.out.println("Device string not found.");
		}
		VoxelSize = buffer.getDouble(12);
		ObjLen = buffer.getDouble(318);
		MeasInfo = getNByteString(buffer, 662);
		MeasDate = buffer.getInt(986);
		PatMeasNo = buffer.getShort(1085);
		PatNo = buffer.getInt(1087);
		PatBirth = buffer.getInt(1091);
		PatName = getNByteString(buffer, 1099);
		PatID = getNByteString(buffer, 1282);
		PicX0 = buffer.getShort(1525);
		PicY0 = buffer.getShort(1527);
		PicMatrixX = buffer.getShort(1529);
		PicMatrixY = buffer.getShort(1531);
	}


	public double[] getScaleCoefficients(){
		
		double[] calibrationCoefficients = new double[2];
		// Read calibration from TYP file database
		String typFileName = Device;
		System.out.println(Device);
		try {
			final ClassLoader loader = getClass().getClassLoader();
			final InputStream ir = loader.getResourceAsStream("typ/" + typFileName);
			final byte[] typFileData = new byte[ir.available()];
			ir.read(typFileData);
			ir.close();
			final String typFiledDataString = new String(typFileData, "ISO-8859-1");
			// break the typFileDataString into lines
			final StringTokenizer st = new StringTokenizer(typFiledDataString,
				"\n");
			final List<String> typFileLines = new Vector<>();
			while (st.hasMoreTokens()) {
				typFileLines.add(st.nextToken());
			}
			// Search for XSlope and XInter
			final String[] searchFor = { "XInter", "XSlope" };
			for (int i = 0; i < searchFor.length; ++i) {
				int index = 0;
				String temp = typFileLines.get(index);
				while (!temp.contains(searchFor[i]) && index < typFileLines.size()) {
					++index;
					temp = typFileLines.get(index);
				}
				if (temp.contains(searchFor[i])) { // Found line
					final StringTokenizer st2 = new StringTokenizer(temp, "=");
					final List<String> typFileLineTokens = new Vector<>();
					while (st2.hasMoreTokens()) {
						typFileLineTokens.add(st2.nextToken().trim());
					}
					calibrationCoefficients[i] = Double.valueOf(typFileLineTokens.get(
						1));
				}
				else {
					calibrationCoefficients[i] = i * 1000.0;
				}
			}
		}
		catch (final Exception e) {
			System.out.println(".TYP file not found "+e.toString());
		}
		return calibrationCoefficients;
	}

	private void readImage(final ByteBuffer buffer) {
		final int size = PicMatrixX * PicMatrixY;
		pixels = new int[size];

		buffer.position(HEADER_LENGTH);
		for (int i = 0; i < size; i++) {
			final int pixel = readSignedShort(buffer);
			final int unsignedShort = pixel & 0xFFFF;
			pixels[i] = unsignedShort;
		}
	}

	private static int readSignedShort(final ByteBuffer buffer) {
		final int bitMask = 0x8000;
		final short pixel = buffer.getShort();
		return (pixel >= 0 ? -bitMask : bitMask - 1) + pixel;
		//short temp =  buffer.getShort();
		//int pixel = 0;
		//pixel = ((0xFF & temp>>8 ) ) | (0Xff & temp) << 8; //Swap pixel order for matlab
		//return pixel;
		
	}

}
