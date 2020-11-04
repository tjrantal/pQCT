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

package sc.fiji.pQCT.io;

import java.util.Arrays;
import java.util.stream.IntStream;

public class ScaledImageData {

	public final double[] scaledImage;
	public final double[] softScaledImage;
	public final double minimum;
	public final int width;
	public final int height;
	public final double pixelSpacing;

	// Constructor
	public ScaledImageData(final int[] data, final int widthIn,
		final int heightIn, final double voxelSize, final double scalingFactor,
		final double constant, final boolean flipHorizontal,
		final boolean flipVertical, final boolean noFiltering)
	{
		height = heightIn;
		width = widthIn;
		pixelSpacing = voxelSize;
		final int filterSize = 3;
		final int size = width * height;
		final double[] unFiltered = IntStream.range(0, size).mapToDouble(
			i -> data[i]).map(x -> x * scalingFactor).map(d -> d + constant)
			.toArray();
		minimum = Arrays.stream(unFiltered).min().orElse(Double.POSITIVE_INFINITY);
		softScaledImage = medianFilter(unFiltered, width, height, 7); // Median
		if (noFiltering) {
			scaledImage = unFiltered;
		}
		else {
			scaledImage = medianFilter(unFiltered, width, height, filterSize); // Median
		}
		if (flipHorizontal) {
			// Flip the image around the horizontal axis...
			flipHorizontally();
		}
		if (flipVertical) {
			// Flip the image around the horizontal axis...
			flipVertically();
		}
	}

	private void flipHorizontally() {
		final long midW = (long) (width / 2.0);
		for (int j = 0; j < height; ++j) {
			final int offset = j * height;
			for (int i = 0; i < midW; ++i) {
				final int sourceIndex = offset + i;
				final int targetIndex = offset + width - 1 - i;
				scaledImage[targetIndex] = scaledImage[sourceIndex];
				softScaledImage[targetIndex] = softScaledImage[sourceIndex];
			}
		}
	}

	private void flipVertically() {
		final long midH = (long) (height / 2.0);
		for (int j = 0; j < midH; ++j) {
			for (int i = 0; i < width; ++i) {
				final int sourceIndex = j * width + i;
				final int targetIndex = (height - j - 1) * width + i;
				scaledImage[targetIndex] = scaledImage[sourceIndex];
				softScaledImage[targetIndex] = softScaledImage[sourceIndex];
			}
		}
	}

	public double[] medianFilter(final double[] data, final int width,
		final int height, final int filterSize)
	{
		// Fill filtered with min value to get the frame from messing up with edge
		// detection
		final double[] filtered = new double[width * height];
		Arrays.fill(filtered, minimum);
		final double[] toMedian = new double[filterSize * filterSize];
		final int noGo = (int) Math.floor(filterSize / 2.0);
		final int median = (int) Math.floor(filterSize * filterSize / 2.0);
		for (int row = noGo; row < height - noGo; row++) {
			for (int col = noGo; col < width - noGo; col++) {
				int newPixel = 0;
				for (int rowOffset = -noGo; rowOffset <= noGo; rowOffset++) {
					for (int colOffset = -noGo; colOffset <= noGo; colOffset++) {
						final int rowTotal = row + rowOffset;
						final int colTotal = col + colOffset;
						toMedian[newPixel] = data[rowTotal * width + colTotal];
						newPixel++;
					}
				}
				Arrays.sort(toMedian);
				filtered[row * width + col] = toMedian[median];
			}
		}
		return filtered;
	}
	
	public double[] sobel(){
		return sobel(scaledImage,width,height);
	}
	
	public static double[] sobel(final double[] data, final int width, final int height){
		double[] output1 = convolve(data,new double[][]{{1,0,-1},{2,0,-2},{1,0,-1}},width,height);
		double[] output2 = convolve(data,new double[][]{{1,2,1},{0,0,0},{-1,-2,-1}},width,height);
		double[] output3 = convolve(data,new double[][]{{2,1,0},{1,0,-1},{0,-1,-2}},width,height);
		double[] output4 = convolve(data,new double[][]{{0,1,2},{-1,0,1},{-2,-1,0}},width,height);
		double[] output = new double[width*height];
		for (int i = 0; i<width*height; ++i){
			output[i] = Math.sqrt(Math.pow(output1[i],2)+Math.pow(output2[i],2));
			//output[i] = Math.sqrt(Math.pow(output1[i],2)+Math.pow(output2[i],2)+Math.pow(output3[i],2)+Math.pow(output4[i],2));
		}
		return output;
	}
	
	private static double[] convolve( double[] data,double[][] kernel, final int width, final int height){
		double[] output = new double[width*height];
		int halfKernelWidth = (int) Math.floor(kernel[0].length/2);
		int halfKernelHeight = (int) Math.floor(kernel.length/2);
		
		for (int r = halfKernelHeight; r<height -halfKernelHeight; ++r){
			for (int c = halfKernelHeight; c<width -halfKernelWidth; ++c){
				for (int r1 = -halfKernelHeight; r1 <=halfKernelHeight;++r1){
					for (int c1 = -halfKernelWidth; c1 <=halfKernelWidth;++c1){
						output[r*width+c] += data[(r+r1)*width+c+c1]*kernel[halfKernelHeight+r1][halfKernelWidth+c1];
					}
				}
			}	
		}
		return output;
	}
}
