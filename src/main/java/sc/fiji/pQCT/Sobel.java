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

public class Sobel implements PlugIn {

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
		
		//Apply 5 x 5 median filter twice - Blew et al data treatment
		double[] sobelFiltered = scaledImageData.sobel(scaledImageData.scaledImage, scaledImageData.width,scaledImageData.height);
		double[] sobelThreshold = new double[sobelFiltered.length];
		for (int i = 0; i<sobelFiltered.length;++i){
			sobelThreshold[i] = scaledImageData.scaledImage[i]*sobelFiltered[i];
		}
		double[] doubleSobel = scaledImageData.sobel(sobelFiltered, scaledImageData.width,scaledImageData.height);

		ImagePlus resultImage = resultImage = getRGBResultImage(sobelFiltered, scaledImageData.width, scaledImageData.height,
			"TestSobel");
		resultImage.setTitle(imp.getTitle() + "-sobel");
		resultImage.show();

		ImagePlus resultImage1 = getRGBResultImage(sobelThreshold, scaledImageData.width, scaledImageData.height,
			"sobelThreshold");
		resultImage1.setTitle(imp.getTitle() + "-sobelThreshold");
		resultImage1.show();

		
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
}
