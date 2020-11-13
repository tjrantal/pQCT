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
import ij.gui.GenericDialog;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.ImageInfo;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.text.TextPanel;
import sc.fiji.pQCT.analysis.ConcentricRingAnalysis;
import sc.fiji.pQCT.analysis.CorticalAnalysis;
import sc.fiji.pQCT.analysis.DetermineAlpha;
import sc.fiji.pQCT.analysis.DistributionAnalysis;
import sc.fiji.pQCT.analysis.MassDistribution;
import sc.fiji.pQCT.analysis.SoftTissueAnalysis;
import sc.fiji.pQCT.io.ImageAndAnalysisDetails;
import sc.fiji.pQCT.io.ScaledImageData;
import sc.fiji.pQCT.selectroi.RoiSelector;
import sc.fiji.pQCT.selectroi.SelectROI;
import sc.fiji.pQCT.selectroi.SelectSoftROI;
import sc.fiji.pQCT.selectroi.SelectSoftROILasso;
import sc.fiji.pQCT.utils.ResultsWriter;
import sc.fiji.pQCT.selectroi.DetectedEdge;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class BlewMA implements PlugIn {

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
		final int[] sectorsAndDivisions = { 10, 3, 10, 10 };
		
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
		
		
		// Get parameters for scaling the image and for thresholding
		final GenericDialog dialog = new GenericDialog("Analysis parameters");
		final String[] topLabels = {"No_filtering"};
		final boolean[] defaultTopValues = new boolean[topLabels.length];
		dialog.addCheckboxGroup(1,(int)  Math.ceil(((double) topLabels.length)/2d), topLabels, defaultTopValues);
		dialog.addNumericField("Low_threshold", 150.0, 4, 8, null); // 550.0
		dialog.	addToSameRow();
		dialog.addNumericField("High_threshold", 710.0, 4, 8, null); // 690.0
		dialog.addNumericField("Scaling_coefficient (slope)", calibrationCoefficients[1], 4, 8, null);
			dialog.	addToSameRow();
		dialog.addNumericField("Scaling_constant (intercept)", calibrationCoefficients[0], 4, 8, null);


		final String[] bottomLabels = new String[2];
		final boolean[] bottomDefaults = new boolean[2];
		bottomLabels[0] = "Suppress_result_image";
		bottomLabels[1] = "Save_visual_result_image_on_disk";
		dialog.addCheckboxGroup(1, 2, bottomLabels, bottomDefaults);

		dialog.addStringField("Image_save_path", Prefs.getDefaultDirectory(), 40);
		dialog.addStringField("Image_save_name", imageName, 20);
		dialog.showDialog();
		if (!dialog.wasOKed()) {
			return;
		}
		for (int i = 0; i < defaultTopValues.length; ++i) {
			defaultTopValues[i] = dialog.getNextBoolean();
		}
		final double[] thresholdsAndScaling = new double[4];
		for (int i = 0; i < thresholdsAndScaling.length; ++i) {
			thresholdsAndScaling[i] = dialog.getNextNumber();
		}
		for (int i = 0; i < bottomDefaults.length; ++i) {
			bottomDefaults[i] = dialog.getNextBoolean();
		}
		final String imageSavePath = dialog.getNextString();
		final String imageSaveName = dialog.getNextString();	//Get file saveName
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
			.getHeight(), resolution, thresholdsAndScaling[2], thresholdsAndScaling[3],
			false, false, defaultTopValues[0]);
		
		//Apply 5 x 5 median filter twice - Blew et al data treatment
		double[] blewFiltered = scaledImageData.medianFilter(scaledImageData.scaledImage, scaledImageData.width,
		scaledImageData.height, 5);	//First iteration
		blewFiltered = scaledImageData.medianFilter(blewFiltered, scaledImageData.width,
		scaledImageData.height, 5); //Second iteration
		
		//Search for the two largest objects with segmentation
		ArrayList<DetectedEdge> lowedges = findEdge(blewFiltered,  scaledImageData.width,
		scaledImageData.height,	thresholdsAndScaling[0], false, false);
		ArrayList<DetectedEdge> highedges = findEdge(blewFiltered,  scaledImageData.width,
		scaledImageData.height,	thresholdsAndScaling[1], false, false);
		
		//Fill in templates with the relevant pixels
		Collections.sort(lowedges);	//Sort in ascending order
		Collections.sort(highedges);	//Sort in ascending order
		byte[] lowMask = new byte[scaledImageData.width*scaledImageData.height];
		byte[] highMask = new byte[scaledImageData.width*scaledImageData.height];
		byte[] tintSieve = new byte[scaledImageData.width*scaledImageData.height];
		
		//Trace the two largest objects (the bones)
		int lowPixels  = 0;
		for (int b = lowedges.size()-2; b<lowedges.size();++b){
			byte[] temp = new byte[scaledImageData.width*scaledImageData.height];
			for (int i = 0;i<lowedges.get(b).iit.size();++i){
				temp[lowedges.get(b).iit.get(i)+lowedges.get(b).jiit.get(i)*scaledImageData.width] = 1;
			}
			Vector<Object> filled = fillResultEdge(temp, scaledImageData.width, scaledImageData.height,	lowedges.get(b).iit,lowedges.get(b).jiit,blewFiltered, thresholdsAndScaling[0]);
			temp = (byte[]) filled.get(0);
			for (int t = 0; t<temp.length;++t){
				if (temp[t] > 0 && blewFiltered[t] > 70){
					lowMask[t] = 1;
					tintSieve[t] = 4;
					++lowPixels;
				}
			}
			
		}
		
		int highPixels = 0;
		for (int b = highedges.size()-2; b<lowedges.size();++b){
			byte[] temp = new byte[scaledImageData.width*scaledImageData.height];
			for (int i = 0;i<highedges.get(b).iit.size();++i){
				temp[highedges.get(b).iit.get(i)+highedges.get(b).jiit.get(i)*scaledImageData.width] = 1;
			}
			Vector<Object> filled = fillResultEdge(temp, scaledImageData.width, scaledImageData.height,	highedges.get(b).iit,highedges.get(b).jiit,blewFiltered, thresholdsAndScaling[1]);
			temp = (byte[]) filled.get(0);
			for (int t = 0; t<temp.length;++t){
				if (temp[t] > 0 && blewFiltered[t] > 70){
					highMask[t] = 1;
					tintSieve[t] = 5;
					++highPixels;
				}
			}
		
		}
		
		/*
		//Produce and show the two threshold images
				
		for (int i = 0;i<scaledImageData.width*scaledImageData.height; ++i){
			if (blewFiltered[i] >= thresholdsAndScaling[0]){
				++lowPixels;
				tintSieve[i] = 4;
				if (blewFiltered[i] >= thresholdsAndScaling[1]){
					++highPixels;
					tintSieve[i] = 5;
				}
			}
		}
		*/
		double percentMove = (((double) lowPixels)/((double) highPixels)-1d)*100d;
		

		TextPanel textPanel = IJ.getTextPanel();
		if (textPanel == null) {
			textPanel = new TextPanel();
		}
		
		//In case this is the first time the plugin has been executed
		if (textPanel.getLineCount() == 0) {
			textPanel.setColumnHeadings("File Name\tLowPixels\tHighPixels\tMove%");
		}
		
		String fileName = "";
		if (getInfoProperty(imageInfo, "File Name") != null) {
			fileName = getInfoProperty(imageInfo,"File Path") + getInfoProperty(imageInfo,"File Name");
		}
		else {
			if (imp.getImageStackSize() == 1) {
				fileName = getInfoProperty(imageInfo,"Title");
			}
			else {
				fileName = imageInfo.substring(0, imageInfo.indexOf("\n"));
			}
		}
		
		String results = String.format("%s\t%d\t%d\t%f",fileName,lowPixels,highPixels,percentMove);
		textPanel.appendLine(results);
		textPanel.updateDisplay();

		ImagePlus resultImage = null;
		boolean makeImage = true;
		if (bottomDefaults[0] && !bottomDefaults[1]) {
			makeImage = false;
		}
		else {
			resultImage = getRGBResultImage(blewFiltered, scaledImageData.width, scaledImageData.height,
				imageSavePath);
			resultImage.setTitle(imp.getTitle() + "-result");
			resultImage = tintSoftTissue(resultImage, tintSieve);
		}
		
		if (!bottomDefaults[0] && resultImage != null) {
			resultImage = drawScale(resultImage, resolution);
			resultImage.show();
		}
		if (bottomDefaults[1] && resultImage != null) {
			if (bottomDefaults[0]) {
				resultImage = drawScale(resultImage, resolution);
			}
			final FileSaver fSaver = new FileSaver(resultImage);
			fSaver.saveAsPng(imageSavePath + imageSaveName + ".png");
		}

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

	private static ImagePlus drawMarrowCenter(final ImagePlus tempImage,
		final double aplha, final double[] marrowCenter)
	{
		for (int i = 0; i < 10; i++) {
			int x = ((int) (marrowCenter[0] + i));
			int y = ((int) (marrowCenter[1]));
			final ImageProcessor processor = tempImage.getProcessor();
			processor.setColor(new Color(0, 255, 255));
			processor.drawPixel(x, y);
			x = (int) (marrowCenter[0]);
			y = (int) (marrowCenter[1] + i);
			processor.setColor(new Color(255, 0, 255));
			processor.drawPixel(x, y);
			// Plot rotated axes...
			final double cos = i * Math.cos(-aplha / 180 * Math.PI);
			final double sin = i * Math.sin(-aplha / 180 * Math.PI);
			x = ((int) (marrowCenter[0] + cos));
			y = ((int) (marrowCenter[1] + sin));
			processor.setColor(new Color(0, 255, 0));
			processor.drawPixel(x, y);
			x = ((int) (marrowCenter[0] - sin));
			y = ((int) (marrowCenter[1] + cos));
			processor.setColor(new Color(0, 0, 255));
			processor.drawPixel(x, y);
		}
		return tempImage;
	}

	// Concentric rings distribution result image
	private static ImagePlus drawPeriRadii(final ImagePlus tempImage,
		final double[] marrowCenter, final Vector<Integer> pindColor,
		final double[] r, final double[] theta)
	{
		// Draw unrotated radii
		for (int i = 0; i < theta.length; i++) {
			final int x = ((int) (marrowCenter[0] + r[i] * Math.cos(theta[i])));
			final int y = ((int) (marrowCenter[1] + r[i] * Math.sin(theta[i])));
			final double colorScale = pindColor.get(i) / 359.0;
			tempImage.getProcessor().setColor(new Color(0, (int) (255.0 * colorScale),
				(int) (255.0 * (1.0 - colorScale))));
			tempImage.getProcessor().drawPixel(x, y);
		}
		return tempImage;
	}

	private static ImagePlus drawRadii(final ImagePlus tempImage,
		final double[] marrowCenter, final Vector<Integer> pindColor,
		final double[] r, final double[] r2, final double[] theta)
	{
		// Draw unrotated radii
		for (int i = 0; i < 360; i++) {
			int x = ((int) (marrowCenter[0] + r[i] * Math.cos(theta[i])));
			int y = ((int) (marrowCenter[1] + r[i] * Math.sin(theta[i])));
			final double colorScale = pindColor.get(i) / 359.0;
			tempImage.getProcessor().setColor(new Color((int) (255.0 * colorScale), 0,
				(int) (255.0 * (1.0 - colorScale))));
			tempImage.getProcessor().drawPixel(x, y);
			x = ((int) (marrowCenter[0] + r2[i] * Math.cos(theta[i])));
			y = ((int) (marrowCenter[1] + r2[i] * Math.sin(theta[i])));
			tempImage.getProcessor().setColor(new Color(0, (int) (255.0 * colorScale),
				(int) (255.0 * (1.0 - colorScale))));
			tempImage.getProcessor().drawPixel(x, y);
		}
		return tempImage;
	}

	private static ImagePlus drawRotated(final ImagePlus tempImage,
		final double alpha)
	{
		tempImage.getProcessor().setBackgroundValue(0.0);
		tempImage.getProcessor().setInterpolationMethod(ImageProcessor.BICUBIC);
		final int width = tempImage.getWidth();
		final int height = tempImage.getHeight();
		final int hypot = (int) Math.sqrt(width * width + height * height);
		final ImageProcessor tIP;
		final int nW = (int) Math.abs(Math.ceil(Math.sin((alpha - 45.0) / 180.0 *
			Math.PI) * hypot));
		final int nH = (int) Math.abs(Math.ceil(Math.cos((alpha - 45.0) / 180.0 *
			Math.PI) * hypot));
		int nSize = Math.max(nW, nH);
		int offs = nSize - width;
		if (offs % 2 != 0) {
			offs++;
		}
		else {
			nSize = nSize + 1;
		}
		offs = offs / 2;
		tIP = expandImage(tempImage.getProcessor(), nSize, nSize, offs, offs);
		tempImage.setProcessor(null, tIP);
		tempImage.getProcessor().rotate(alpha);
		return tempImage;
	}

	private static ImagePlus drawScale(final ImagePlus tempImage,
		final double pixelSpacing)
	{
		final Calibration cal = new Calibration();
		cal.setUnit("mm");
		cal.pixelWidth = cal.pixelHeight = pixelSpacing;
		tempImage.setCalibration(cal);
		tempImage.getProcessor().setColor(new Color(255, 0, 0));
		tempImage.getProcessor().drawLine(5, 5, (int) (5.0 + 10.0 / pixelSpacing),
			5);
		tempImage.getProcessor().drawString("1 cm", 5, 20);
		return tempImage;
	}

	// TODO Can be called from there?
	// Function taken from ij.plugin.CanvasResizer
	private static ImageProcessor expandImage(final ImageProcessor ipOld,
		final int wNew, final int hNew, final int xOff, final int yOff)
	{
		final ImageProcessor ipNew = ipOld.createProcessor(wNew, hNew);
		ipNew.setColor(new Color(0.0f, 0.0f, 0.0f));
		ipNew.setBackgroundValue(0.0);
		ipNew.fill();
		ipNew.insert(ipOld, xOff, yOff);
		return ipNew;
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

	private static String printAlpha(String results,
		final DetermineAlpha determineAlpha)
	{
		results += Double.toString(determineAlpha.alpha * 180 / Math.PI) + "\t";
		results += Double.toString(determineAlpha.rotationCorrection) + "\t";
		results += Double.toString(determineAlpha.distanceBetweenBones) + "\t";
		return results;
	}

	private static String printConcentricRingResults(final String results,
		final ConcentricRingAnalysis ringAnalysis,
		final ImageAndAnalysisDetails details)
	{
		final int limit = 360 / details.concentricSector;
		final StringBuilder resultsBuilder = new StringBuilder(results);
		for (int i = 0; i < limit; ++i) {
			resultsBuilder.append(ringAnalysis.pericorticalRadii[i]).append("\t");
		}
		for (int j = 0; j < details.concentricDivisions; ++j) {
			for (int i = 0; i < limit; ++i) {
				resultsBuilder.append(ringAnalysis.BMDs.get(j)[i]).append("\t");
			}
		}
		return resultsBuilder.toString();
	}

	private static String printCorticalResults(final String results,
		final CorticalAnalysis cortAnalysis)
	{
		final StringBuilder builder = new StringBuilder(results);
		//IJ.log("Building resultsString");
		DoubleStream.of(cortAnalysis.maMassD, cortAnalysis.stratecMaMassD,
			cortAnalysis.marrowDensity, cortAnalysis.marrowArea, cortAnalysis.bMD,
			cortAnalysis.area, cortAnalysis.CoD, cortAnalysis.CoA, cortAnalysis.sSI,
			cortAnalysis.sSIMax, cortAnalysis.sSIMin, cortAnalysis.iPo,
			cortAnalysis.iMax, cortAnalysis.iMin, cortAnalysis.dwIPo,
			cortAnalysis.dwIMax, cortAnalysis.dwIMin, cortAnalysis.ToD,
			cortAnalysis.ToA, cortAnalysis.medullaryArea, cortAnalysis.bSId, 
			cortAnalysis.peeledTrD, cortAnalysis.peeledTrA,cortAnalysis.TrD, cortAnalysis.TrA).mapToObj(
				Double::toString).forEach(s -> builder.append(s).append("\t"));
				
				//IJ.log("Built string "+builder.toString());
		return builder.toString();
	}

	private static String printDistributionResults(final String results,
		final DistributionAnalysis distributionAnalysis,
		final ImageAndAnalysisDetails details)
	{
		final StringBuilder resultsBuilder = new StringBuilder(results);
		resultsBuilder.append(distributionAnalysis.peeledBMD).append("\t");
		// Radial distribution
		for (int i = 0; i < details.divisions; ++i) {
			resultsBuilder.append(distributionAnalysis.radialDistribution[i]).append(
				"\t");
		}
		final int iterations = 360 / details.sectorWidth;
		// Polar distribution
		for (int i = 0; i < iterations; ++i) {
			resultsBuilder.append(distributionAnalysis.polarDistribution[i]).append(
				"\t");
		}

		for (int pp = 0; pp < iterations; ++pp) {
			resultsBuilder.append(distributionAnalysis.endocorticalRadii[pp]).append(
				"\t");
		}
		for (int pp = 0; pp < iterations; ++pp) {
			resultsBuilder.append(distributionAnalysis.pericorticalRadii[pp]).append(
				"\t");
		}
		// Cortex bMD values
		for (int pp = 0; pp < iterations; ++pp) {
			resultsBuilder.append(distributionAnalysis.endoCorticalBMDs[pp]).append(
				"\t");
		}
		for (int pp = 0; pp < iterations; ++pp) {
			resultsBuilder.append(distributionAnalysis.midCorticalBMDs[pp]).append(
				"\t");
		}
		for (int pp = 0; pp < iterations; ++pp) {
			resultsBuilder.append(distributionAnalysis.periCorticalBMDs[pp]).append(
				"\t");
		}
		return resultsBuilder.toString();
	}

	private static String printMassDistributionResults(final String results,
		final MassDistribution massDistribution,
		final ImageAndAnalysisDetails details)
	{
		final StringBuilder resultsBuilder = new StringBuilder(results);
		for (int pp = 0; pp < (360 / details.sectorWidth); pp++) {
			resultsBuilder.append(massDistribution.bMCs[pp]).append("\t");
		}
		return resultsBuilder.toString();
	}

	private static String printSoftTissueResults(String results,
		final SoftTissueAnalysis softTissueAnalysis)
	{
		results += softTissueAnalysis.totalMuD + "\t";
		results += softTissueAnalysis.totalMuA + "\t";
		results += softTissueAnalysis.muD + "\t";
		results += softTissueAnalysis.muA + "\t";
		results += softTissueAnalysis.intraMuFatD + "\t";
		results += softTissueAnalysis.intraMuFatA + "\t";
		results += softTissueAnalysis.fatD + "\t";
		results += softTissueAnalysis.fatA + "\t";
		results += softTissueAnalysis.subCutFatDMedian + "\t";
		results += softTissueAnalysis.subCutFatD + "\t";
		results += softTissueAnalysis.subCutFatA + "\t";

		results += softTissueAnalysis.meD + "\t";
		results += softTissueAnalysis.meA + "\t";
		results += softTissueAnalysis.boneD + "\t";
		results += softTissueAnalysis.boneA + "\t";
		results += softTissueAnalysis.peeledD + "\t";
		results += softTissueAnalysis.peeledA + "\t";

		results += softTissueAnalysis.limbD + "\t";
		results += softTissueAnalysis.limbA + "\t";
		results += softTissueAnalysis.fatPercentage + "\t";
		return results;
	}

	// Add bone sieve Stratec
	private static ImagePlus tintBoneStratec(final ImagePlus tempImage,
		final byte[] sieve, final double[] scaledImage,
		final double marrowThreshold, final byte[] stratecSieve)
	{
		for (int y = 0; y < tempImage.getHeight(); ++y) {
			for (int x = 0; x < tempImage.getWidth(); ++x) {
				final int value = tempImage.getProcessor().getPixel(x, y);
				final int[] rgb = new int[3];
				for (int i = 0; i < 3; ++i) {
					rgb[i] = (value >> (i * 8)) & 0XFF;
				}
				final int index = x + y * tempImage.getWidth();
				if (sieve[index] == 1) {
					// Tint bone area with purple
					tempImage.getProcessor().setColor(new Color(rgb[2], 0, rgb[0]));
					if (scaledImage[index] <= marrowThreshold) {
						// Tint marrow area with green
						if (rgb[0] < 255 - 50) {
							rgb[0] += 50;
						}
						tempImage.getProcessor().setColor(new Color(0, 0, rgb[0]));
					}
					tempImage.getProcessor().drawPixel(x, y);
				}
				if (stratecSieve[index] == 1) {
					// Tint stratec bone area with cyan
					tempImage.getProcessor().setColor(new Color(0, rgb[0], rgb[0]));
					tempImage.getProcessor().drawPixel(x, y);
				}
			}
		}
		return tempImage;
	}

	private static ImagePlus tintSoftTissue(final ImagePlus tempImage,
		final byte[] sieve)
	{
		for (int y = 0; y < tempImage.getHeight(); ++y) {
			for (int x = 0; x < tempImage.getWidth(); ++x) {
				final int value = tempImage.getProcessor().getPixel(x, y);
				final int[] rgb = new int[3];
				for (int i = 0; i < 3; ++i) {
					rgb[i] = (value >> (i * 8)) & 0XFF;
				}
				final byte pixel = sieve[x + y * tempImage.getWidth()];
				final Color sieveColor;
				switch (pixel) {
					case 2:
						sieveColor = new Color(rgb[2], rgb[1], 0);
						break;
					case 3:
						sieveColor = new Color(rgb[2], 0, 0);
						break;
					case 4:
						sieveColor = new Color(0, rgb[1], 0);
						break;
					case 5:
						sieveColor = new Color(rgb[2], 0, rgb[0]);
						break;
					default:
						continue;
				}
				tempImage.getProcessor().setColor(sieveColor);
				tempImage.getProcessor().drawPixel(x, y);
			}
		}
		return tempImage;
	}
	

	
	//**Edge tracing*/
	// DetectEdge
	private ArrayList<DetectedEdge> findEdge(final double[] scaledImage, int width, int height,
		final double threshold, final boolean allowCleaving, final boolean grTrack)
	{
		int i = 0;
		int j = 0;
		int tempI;
		int tempJ;
		byte[] result = new byte[scaledImage.length];
		final ArrayList<DetectedEdge> edges = new ArrayList<DetectedEdge>();
		while ((i < (width - 1)) && (j < (height - 1))) {
			while (j < height - 1 && i < width && scaledImage[i + j *
				width] < threshold)
			{
				i++;
				if (result[i + j * width] == 1) {
					while (j < height - 1 && result[i + j * width] > 0) {
						i++;
						if (i == width && j < height - 2) {
							i = 0;
							j++;
						}

					}
				}
				if (i == width) {
					j++;
					if (j >= height - 1) break;
					i = 0;
				}
			}
			tempI = i;
			tempJ = j;

			if (i >= width - 1 && j >= height - 1) {
				break; /*Go to end...*/
			}
			result[i + j * width] = 1;

			// Tracing algorithm 
			Vector<Object> returned = null;
			if (!grTrack){
				returned = traceEdge(scaledImage, width, height, result, threshold, i, j); //Contour tracing
			}else{
				//returned = traceGradient(scaledImage, result, threshold, i, j); //Contour tracing		
			}
			result = (byte[]) returned.get(0);
			final Vector<Integer> newIit = (Vector<Integer>) returned.get(1);
			final Vector<Integer> newJiit = (Vector<Integer>) returned.get(2);
			// Tracing algorithm done...

			if (allowCleaving) {
				/*
				final Vector<Vector<Vector<Integer>>> returnedVectors = cleaveEdge(
					result, newIit, newJiit, 3.0, 6.0);
				for (final Vector<Vector<Integer>> returnedVector : returnedVectors) {
					// Fill edge within result..
					final Vector<Integer> iit = new Vector<>();
					final Vector<Integer> jiit = new Vector<>();
					for (int ii = 0; ii < returnedVector.get(0).size(); ++ii) {
						iit.add(returnedVector.get(0).get(ii));
						jiit.add(returnedVector.get(1).get(ii));
					}
					final Vector<Object> results = fillResultEdge(result,width,height, iit, jiit,
						scaledImage, threshold);
					if (results != null) {
						result = (byte[]) results.get(0);
						edges.add(new DetectedEdge((Vector<Integer>) results.get(1),
							(Vector<Integer>) results.get(2), (Integer) results.get(3)));
					}
				}
				*/
			}
			else {
				// Fill edge within result..
				final Vector<Integer> iit = new Vector<>();
				final Vector<Integer> jiit = new Vector<>();
				for (int ii = 0; ii < newIit.size(); ++ii) {
					iit.add(newIit.get(ii));
					jiit.add(newJiit.get(ii));
				}
				final Vector<Object> results = fillResultEdge(result,width,height, iit, jiit,
					scaledImage, threshold);
				if (results != null) {
					result = (byte[]) results.get(0);
					edges.add(new DetectedEdge((Vector<Integer>) results.get(1),
						(Vector<Integer>) results.get(2), (Integer) results.get(3)));
				}
			}
			// Find next empty spot
			i = tempI;
			j = tempJ;
			while (j < height && scaledImage[i + j * width] >= threshold) {
				i++;
				if (i == width) {
					i = 0;
					j++;
				}
			}
		}

		return edges;
	}
	
	/*	Edge Tracing DetectedEdge
	trace edge by advancing according to the previous direction
	if above threshold, turn to negative direction
	if below threshold, turn to positive direction
	Idea taken from http://www.math.ucla.edu/~bertozzi/RTG/zhong07/report_zhong.pdf
	The paper traced continent edges on map/satellite image
	*/
	private Vector<Object> traceEdge(final double[] scaledImage, int width, int height,
		final byte[] result, final double threshold, int i, int j)
	{
		final Collection<Integer> iit = new Vector<>();
		final Collection<Integer> jiit = new Vector<>();
		iit.add(i);
		jiit.add(j);
		// begin by advancing right. Positive angles rotate the direction clockwise.
		double direction = 0;
		double previousDirection;
		final int initI;
		final int initJ;
		initI = i;
		initJ = j;
		while (true) {
			int counter = 0;
			previousDirection = direction;
			// Handle going out of bounds by considering out of bounds to be less than
			// threshold
			if ((i + ((int) Math.round(Math.cos(direction)))) >= 0 && (i + ((int) Math
				.round(Math.cos(direction))) < width) && (j + ((int) Math.round(Math
					.sin(direction))) >= 0) && (j + ((int) Math.round(Math.sin(
						direction))) < height) && scaledImage[i + ((int) Math.round(Math
							.cos(direction))) + (j + ((int) Math.round(Math.sin(
								direction)))) * width] > threshold)
			{
				// Rotate counter clockwise
				while (counter < 8 && i + ((int) Math.round(Math.cos(direction -
					Math.PI / 4.0))) >= 0 && i + ((int) Math.round(Math.cos(direction -
						Math.PI / 4.0))) < width && j + ((int) Math.round(Math.sin(
							direction - Math.PI / 4.0))) >= 0 && j + ((int) Math.round(Math
								.sin(direction - Math.PI / 4.0))) < height && scaledImage[i +
									((int) Math.round(Math.cos(direction - Math.PI / 4.0))) + (j +
										((int) Math.round(Math.sin(direction - Math.PI / 4.0)))) *
										width] > threshold)
				{
					direction -= Math.PI / 4.0;
					++counter;
					if (Math.abs(direction - previousDirection) >= 180) {
						break;
					}
				}
			}
			else {
				// Rotate clockwise
				while (counter < 8 && (i + ((int) Math.round(Math.cos(
					direction))) < 0 || i + ((int) Math.round(Math.cos(
						direction))) >= width || j + ((int) Math.round(Math.sin(
							direction))) < 0 || j + ((int) Math.round(Math.sin(
								direction))) >= height || scaledImage[i + ((int) Math.round(Math
									.cos(direction))) + (j + ((int) Math.round(Math.sin(
										direction)))) * width] < threshold))
				{
					direction += Math.PI / 4.0;
					++counter;
					if (Math.abs(direction - previousDirection) >= 180) {
						break;
					}
				}

			}
			i += (int) Math.round(Math.cos(direction));
			j += (int) Math.round(Math.sin(direction));
			if ((i == initI && j == initJ) || counter > 7 || scaledImage[i + j *
				width] < threshold || result[i + j * width] == 1 || result[i + j *
					width] > 3)
			{
				for (int ii = 0; ii < result.length; ++ii) {
					if (result[ii] > 1) {
						result[ii] = 1;
					}
				}
				final Vector<Object> returnVector = new Vector<>();
				returnVector.add(result);
				returnVector.add(iit);
				returnVector.add(jiit);
				return returnVector;
			}
			else {
				if (result[i + j * width] == 0) {
					result[i + j * width] = 2;
				}
				else if (result[i + j * width] != 1) {
					result[i + j * width]++;
				}
				iit.add(i);
				jiit.add(j);

			}
			// Keep steering counter clockwise not to miss single pixel structs...
			direction -= Math.PI / 2.0;
		}
	}

	// DetectedEdge version
	private Vector<Object> fillResultEdge(byte[] result, int width, int height,
		final Vector<Integer> iit, final Vector<Integer> jiit,
		final double[] scaledImage, final double threshold)
	{
		if (iit.isEmpty()) {
			return null;
		}
		Vector<Object> results = null;
		int pixelsFilled = 0;
		// Set initial fill pixel to the first pixel above threshold not on the
		// border
		boolean possible = true;
		final byte[] tempResult = result.clone();
		int[] tempCoordinates = findFillInit(tempResult,width,height, iit, jiit, scaledImage,
			threshold);
		while (possible && tempCoordinates != null) {
			final Vector<Object> returned = resultFill(tempCoordinates[0],
				tempCoordinates[1], tempResult,width,height);
			possible = (Boolean) returned.get(0);
			pixelsFilled += (Integer) returned.get(1);
			tempCoordinates = findFillInit(tempResult,width,height, iit, jiit, scaledImage,
				threshold);
		}
		if (possible) {
			results = new Vector<>();
			result = tempResult;
			results.add(result);
			results.add(iit);
			results.add(jiit);
			results.add(pixelsFilled);
		}
		return results;
	}	
	
	// DetectedEdge. Find fill init by steering clockwise from next to previous
	private int[] findFillInit(final byte[] result, int width, int height, final Vector<Integer> iit,
		final Vector<Integer> jiit, final double[] scaledImage,
		final double threshold)
	{
		final int[] returnCoordinates = new int[2];
		final int[] steer = new int[2];
		for (int j = 0; j < iit.size() - 1; ++j) {
			returnCoordinates[0] = iit.get(j);
			returnCoordinates[1] = jiit.get(j);
			double direction = Math.atan2(jiit.get(j + 1) - returnCoordinates[1], iit
				.get(j + 1) - returnCoordinates[0]);
			for (int i = 0; i < 8; ++i) {
				direction += Math.PI / 4.0;
				steer[0] = (int) Math.round(Math.cos(direction));
				steer[1] = (int) Math.round(Math.sin(direction));
				/*Handle OOB*/
				while ((returnCoordinates[0] + steer[0]) < 0 || (returnCoordinates[0] +
					steer[0]) >= width || (returnCoordinates[1] + steer[1]) < 0 ||
					(returnCoordinates[1] + steer[1]) >= height)
				{
					direction += Math.PI / 4.0;
					steer[0] = (int) Math.round(Math.cos(direction));
					steer[1] = (int) Math.round(Math.sin(direction));
				}

				if (result[returnCoordinates[0] + steer[0] + (returnCoordinates[1] +
					steer[1]) * width] == 0 && scaledImage[returnCoordinates[0] +
						steer[0] + (returnCoordinates[1] + steer[1]) * width] >= threshold)
				{
					returnCoordinates[0] += steer[0];
					returnCoordinates[1] += steer[1];
					return returnCoordinates;
				}
				if (result[returnCoordinates[0] + steer[0] + (returnCoordinates[1] +
					steer[1]) * width] == 1)
				{
					break;
				}
			}
		}
		return null;
	}
	
	private Vector<Object> resultFill(int i, int j, final byte[] tempResult, int width, int height) {
		final Vector<Integer> initialI = new Vector<>();
		final Vector<Integer> initialJ = new Vector<>();
		initialI.add(i);
		initialJ.add(j);
		int pixelsFilled = 0;
		while (!initialI.isEmpty() && initialI.lastElement() > 0 && initialI
			.lastElement() < width - 1 && initialJ.lastElement() > 0 && initialJ
				.lastElement() < height - 1)
		{
			i = initialI.lastElement();
			j = initialJ.lastElement();
			initialI.remove(initialI.size() - 1);
			initialJ.remove(initialJ.size() - 1);

			if (tempResult[i + j * width] == 0) {
				tempResult[i + j * width] = 1;
				++pixelsFilled;
			}

			if (tempResult[i - 1 + j * width] == 0) {
				initialI.add(i - 1);
				initialJ.add(j);
			}

			if (tempResult[i + 1 + j * width] == 0) {
				initialI.add(i + 1);
				initialJ.add(j);
			}

			if (tempResult[i + (j - 1) * width] == 0) {
				initialI.add(i);
				initialJ.add(j - 1);
			}

			if (tempResult[i + (j + 1) * width] == 0) {
				initialI.add(i);
				initialJ.add(j + 1);
			}

		}
		final Vector<Object> returnValue = new Vector<>();
		returnValue.add(initialI.isEmpty() && initialJ.isEmpty());
		returnValue.add(pixelsFilled);
		return returnValue;
	}
}
