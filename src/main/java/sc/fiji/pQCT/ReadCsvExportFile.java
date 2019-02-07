/*
BSD 2-Clause License

Copyright (c) 2018-2019, Timo Rantalainen
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

//import java.io.BufferedInputStream;
//import java.io.DataInputStream;
//import java.io.File;
//import java.io.FileInputStream;
import java.io.IOException;
//import java.nio.ByteBuffer;
//import java.nio.ByteOrder;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.StringTokenizer;
import java.util.ArrayList;
import java.text.NumberFormat;
import java.util.Locale;

import javax.activation.UnsupportedDataTypeException;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.NewImage;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;

// This file format is supported in SCIFIO already,
// but we'll keep this plugin around for people who don't have it enabled.
// Also SCIFIO is as of yet experimental code.
// TODO Remove when ImageJ/Fiji comes with SCIFIO enabled by default
public class ReadCsvExportFile extends ImagePlus implements PlugIn {
	
	private String PatName;
	private long PatNo = -1l;
	private int PatMeasNo = -1;
	private long PatBirth = -1l;
	private long MeasDate = -1l;
	private double VoxelSize = 0.5d;
	private int PicX0 = -1;
	private int PicY0 = -1;
	private int PicMatrixX;
	private int PicMatrixY;
	private String MeasInfo = "";
	private String Device = "XCT3010.TYP";
	private String PatID = "";
	private double ObjLen = -1d;
	private String fileName;
	private String properties;
	private String delimiter = ";";
	private NumberFormat nf;

	@Override
	public void run(final String arg) {
		final String path;
		if (!arg.isEmpty()) {
			// Called by HandleExtraFileTypes
			final File file = new File(arg);
			path = file.getParent() + "/";
			this.fileName = file.getName();
		}
		else {
			// Select file manually
			final OpenDialog od = new OpenDialog("Select stratec CSV export image (*.CSV)", arg);
			path = od.getDirectory();
			fileName = od.getFileName();
		}
		if (fileName == null) return;
		readFile(path);
		fileInfo();
		if (arg.isEmpty() && getHeight() > 0) {
			show();
		}
	}

	private void fileInfo() {
		FileInfo fi = getFileInfo();
		if (fi == null) {
			fi = new FileInfo();
		}
		fi.pixelWidth = VoxelSize;
		fi.pixelHeight = VoxelSize;
		fi.width = PicMatrixX;
		fi.height = PicMatrixY;
		fi.valueUnit = "mm";
		fi.fileName = fileName;
		fi.info = properties;
		fi.fileFormat = FileInfo.RAW;
		fi.compression = FileInfo.COMPRESSION_NONE;
		fi.fileType = FileInfo.GRAY16_SIGNED;
		setFileInfo(fi);
	}


	private void readFile(final String path) {
		final File file = new File(path + fileName);
		
		nf = NumberFormat.getNumberInstance(Locale.ROOT);
		
		final int bytes = (int) file.length();
		if (bytes < 100) {
			IJ.error("Reading the CSV file failed: File length < 100 bytes.");
			return;
		}
		
		//JATKA TÄSTÄ
		try{
			BufferedReader br = new BufferedReader(new FileReader(file));
			String strLine = "";
			StringTokenizer st = null;
			ArrayList<ArrayList<String>> data  = new ArrayList<ArrayList<String>>();
			//IJ.log("Start reading data");
			while((strLine = br.readLine()) != null){
				st = new StringTokenizer(strLine, delimiter);
				data.add(new ArrayList<String>());
				while(st.hasMoreTokens()){
				  data.get(data.size()-1).add(new String(st.nextToken().trim()));
				}
				//IJ.log(String.format("Read row %d columns %d",data.size(),data.get(data.size()-1).size()));
			}
			br.close();
			//Log.e(TAG,"File read count "+cnt);
			//Log.e(TAG,"File read rows "+data.get(0).size()+" columns "+data.size());					
			
			dataToPrimitive(path,data);	//Pop the data into primitives
			//Release memory
			for (int i = 0;i<data.size();++i){
				data.get(i).clear();
			}
			data.clear();
			
		}catch(Exception e){}
		
	}

	public void dataToPrimitive(String path, ArrayList<ArrayList<String>> data){
		try{
			//Pop data into arrays; Discard first and last datapoint
			PicMatrixY = data.size();
			PicMatrixX = data.get(0).size();
			//IJ.log(String.format("Start popping data to an array w %d h %d",PicMatrixX,PicMatrixX));
			final ImagePlus tempImage = NewImage.createShortImage(fileName + " " + Double.toString(VoxelSize), PicMatrixX, PicMatrixY, 1,	NewImage.FILL_BLACK);
			setImage(tempImage.getImage());
			setProcessor(fileName, tempImage.getProcessor());
			setProperties(path);
			
			
			
			final short[] pixels = (short[]) getProcessor().getPixels();
			final int size = PicMatrixX * PicMatrixY;
			int min = Short.MAX_VALUE;
			int max = Short.MIN_VALUE;
			
			for (int y = 0; y < PicMatrixY; ++y) {
				for (int x = 0; x < PicMatrixX; ++x){
					pixels[y*PicMatrixX +x] =(short) ((1<<15)+ nf.parse(data.get(y).get(x)).shortValue());
					min = Math.min(min, 0xFFFF & pixels[y*PicMatrixX +x]);
					max = Math.max(max, 0xFFFF & pixels[y*PicMatrixX +x]);
				}
			}
			//IJ.log(String.format("min %d max %d %d %d",min,max,(1<<15)-100, (1<<15)+1100));
			
			//setDisplayRange((1<<15)-100, (1<<15)+1100);
			setDisplayRange(min, max);
			
			final Calibration cal = getCalibration();
			final double[] coefficients = { -32.768, 0.001f };
			cal.setFunction(Calibration.STRAIGHT_LINE, coefficients, "1/cm");
			cal.setUnit("mm");
			cal.pixelWidth = cal.pixelHeight = cal.pixelDepth = VoxelSize;
			
		}catch (Exception e){
			/*Do nothing*/
		}
	}

	private void setProperties(final String directory) {
		final String[] propertyNames = { "File Name", "File Path", "Pixel Spacing",
			"ObjLen", "MeasInfo", "Acquisition Date", "Device", "PatMeasNo", "PatNo",
			"Patient's Birth Date", "Patient's Name", "Patient ID", "PicX0", "PicY0",
			"Width", "Height", "Stratec File" };
		final String[] propertyValues = { fileName, directory, Double.toString(
			VoxelSize), Double.toString(ObjLen), MeasInfo, Long.toString(MeasDate),
			Device, Integer.toString(PatMeasNo), Long.toString(PatNo), Long.toString(
				PatBirth), PatName, PatID, Integer.toString(PicX0), Integer.toString(
					PicY0), Integer.toString(PicMatrixX), Integer.toString(PicMatrixY),
			"1" };
		final StringBuilder builder = new StringBuilder();
		for (int i = 0; i < propertyNames.length; ++i) {
			builder.append(propertyNames[i]).append(": ").append(propertyValues[i])
				.append("\n");
		}
		properties = builder.toString();
		setProperty("Info", properties);
	}
}
