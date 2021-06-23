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

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.stream.DoubleStream;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.ImageInfo;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import sc.fiji.pQCT.io.ImageAndAnalysisDetails;
import sc.fiji.pQCT.io.ScaledImageData;

//Clustering
import sc.fiji.pQCT.selectroi.Coordinate;
import java.util.ArrayList;
import sc.fiji.pQCT.utils.ClusterPoints;

public class Cluster implements PlugIn {

	@Override
	public void run(final String arg) {
		final ImagePlus imp = WindowManager.getCurrentImage();
		if (imp == null) return;
		if (imp.getType() != ImagePlus.GRAY16) {
			IJ.error("Distribution analysis expects 16-bit greyscale data");
			return;
		}
		// Set sector widths and division numbers
		// Distribution analysis sectorWidth, Distribution analysis sectors,
		// Concentric distribution analysis sectorWidth, Concentric distribution
		// analysis sectors
				
		//A ROI appears on the image every now and then, haven't figured out why -> remove any unwanted rois prior to soft-tissue analysis. If no ROIs are to be found when starting the analysis remove any that appear subsequently
		byte removeROIs = 0;
		if (imp.getRoi() == null){
			removeROIs = 1;
		}
		

		String imageInfo = new ImageInfo().getImageInfo(imp);
		// Check image calibration
		final Calibration cal = imp.getCalibration();
		double[] calibrationCoefficients = { 0, 1 };
		if (getInfoProperty(imageInfo, "Stratec File") == null) {
			if (cal != null && cal.getCoefficients() != null) {
				calibrationCoefficients = cal.getCoefficients();
			}
		}
		else {
			calibrationCoefficients = new double[2];
			// Read calibration from TYP file database
			final String typFileName = getInfoProperty(imageInfo, "Device");
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
				calibrationCoefficients[1] /= 1000.0; // 1.495
			}
			catch (final NullPointerException npe) {
				IJ.log(".TYP file not found");
			}
			catch (final IOException e) {
				IJ.error(".TYP file could not be read");
			}
		}
		double resolution = cal.pixelWidth;
		if (getInfoProperty(imageInfo, "Pixel Spacing") != null) {
			String temp = getInfoProperty(imageInfo, "Pixel Spacing");
			if (temp.contains("\\")) {
				temp = temp.substring(0, temp.indexOf("\\"));
			}
			resolution = Double.valueOf(temp);
		}
		
		String imageName = getInfoProperty(imageInfo, "File Name");
		if (imageName == null) {
			if (imp.getImageStackSize() == 1) {
				imageName = imp.getTitle();
			}
			else {
				imageName = imageInfo.substring(0, imageInfo.indexOf("\n"));
			}
			imageInfo += "File Name:" + imageName + "\n";
		}
		
		final ScaledImageData scaledImageData;

		

		final short[] tempPointer = (short[]) imp.getProcessor().getPixels();
		final int[] signedShort = new int[tempPointer.length];
		final float[] floatPointer = (float[]) imp.getProcessor().toFloat(1, null)
			.getPixels();
		if (imp.getOriginalFileInfo().fileType == ij.io.FileInfo.GRAY16_SIGNED ||
			cal.isSigned16Bit())
		{
			for (int i = 0; i < tempPointer.length; ++i) {
				signedShort[i] = (int) (floatPointer[i] - Math.pow(2.0, 15.0));
			}
		}
		else {
			/*
			Apply the original calibration of the image prior to applying the calibration got from the user
			-> enables using ImageJ for figuring out the calibration without too much fuss.
			*/
			try {
				double[] origCalCoeffs = imp.getOriginalFileInfo().coefficients;
				if (origCalCoeffs == null) {
					origCalCoeffs = cal.getCoefficients();
				}
				for (int i = 0; i < tempPointer.length; ++i) {
					signedShort[i] = (int) (floatPointer[i] * origCalCoeffs[1] +
						origCalCoeffs[0]);
				}
			}
			catch (final Exception err) {
				for (int i = 0; i < tempPointer.length; ++i) {
					signedShort[i] = tempPointer[i];
				}
			}
		}

		// Scale and 3x3 median filter the data
		scaledImageData = new ScaledImageData(signedShort, imp.getWidth(), imp
			.getHeight(), resolution, calibrationCoefficients[1], calibrationCoefficients[0],
			false, false, true);
			
		IJ.log("Got Image scaled");
		if (false){
			ImagePlus resultImage = getRGBResultImage(scaledImageData.scaledImage, scaledImageData.width, scaledImageData.height,
				"Clusters");
			resultImage.setTitle(imp.getTitle() + "-orig");
			resultImage.show();
		}
		
		//Try clustering here
		ArrayList<Coordinate> testCoordinates = new ArrayList<Coordinate>();
		for (int i = 0; i<imp.getHeight(); ++i){
			for (int j = 0; j<imp.getWidth(); ++j){
				//Add coordinates if it is a bone pixel
				if (scaledImageData.scaledImage[i*imp.getWidth()+j] > 280){
					testCoordinates.add(new  Coordinate(j,i));
				}
			}
		}
		IJ.log(String.format("Amalgamated test coordinates %d",testCoordinates.size()));
		
		ClusterPoints cp = new ClusterPoints(testCoordinates);
		
		ImagePlus resultImage = getRGBResultImage(scaledImageData.scaledImage, scaledImageData.width, scaledImageData.height,
			"Clusters");
		resultImage.setTitle(imp.getTitle() + "-cluster");
		
		//Add cluster Tints
		resultImage = tintBoneCluster(resultImage,	cp.cluster1,new double[]{1,0,0});
		resultImage = tintBoneCluster(resultImage,	cp.cluster2,new double[]{0,0,1});
		
		resultImage.show();
		
		
	}

	public static String getInfoProperty(final String properties,
		final CharSequence propertyToGet)
	{
		final StringTokenizer st = new StringTokenizer(properties, "\n");
		String currentToken = null;
		while (st.hasMoreTokens()) {
			currentToken = st.nextToken();
			if (currentToken.contains(propertyToGet)) {
				break;
			}
		}
		if (currentToken == null) {
			return null;
		}

		final StringTokenizer st2 = new StringTokenizer(currentToken, ":");
		String token2 = null;
		while (st2.hasMoreTokens()) {
			token2 = st2.nextToken();
		}
		return token2 != null ? token2.trim() : null;
	}

	
	

	// Get image into which we'll start adding stuff
	private static ImagePlus getRGBResultImage(final double[] values,
		final int width, final int height, final String path)
	{
		final ImagePlus tempImage = new ImagePlus();
		tempImage.setTitle(path + "Visual results");
		tempImage.setProcessor(new FloatProcessor(width, height, values));
		new ImageConverter(tempImage).convertToRGB();
		return tempImage;
	}

	

	// Add colours for clusters
	private static ImagePlus tintBoneCluster(final ImagePlus tempImage,	ArrayList<Coordinate> coordinates,double[] colourMultiplier){
		for (int c = 0; c<coordinates.size();++c){
			int x = (int) coordinates.get(c).ii,y = (int) coordinates.get(c).jj;
			final int value = tempImage.getProcessor().getPixel(x,y);
			final int[] rgb = new int[3];
			for (int i = 0; i < 3; ++i) {
				rgb[i] = (int) (((value >> (i * 8)) & 0XFF)*colourMultiplier[i]);
			}
			tempImage.getProcessor().setColor(new Color(rgb[0], rgb[1], rgb[2]));
			tempImage.getProcessor().drawPixel(x,y);
		}
		return tempImage;
	}
	
}
