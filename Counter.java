/**
 * @author Cameron Gillespie (1330284); Utsav Joshi (1534334);
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * An image processing class that reads an 8 bit greyscale image of cells and counts the number of cells present in that
 * image and displays the image after each operation in the image pipeline using a GUI.
 */
public class Counter {
  private static final int FILTER_WIDTH = 3;
  private static final int FILTER_HEIGHT = 3;
  private static final int FILTER_SIZE = FILTER_WIDTH * FILTER_HEIGHT;
  private static final int MIN_INTENSITY = 0;
  private static final int MAX_INTENSITY = 255;
  private static final int BACKGROUND_LABEL = 0;
  private static final int FOREGROUND_LABEL = 1;
  private static final int BACKGROUND_PIXEL_VALUE = getPixelValue(MAX_INTENSITY);
  private static final int FOREGROUND_PIXEL_VALUE = getPixelValue(MIN_INTENSITY);
  private static final int TOTAL_PIXELS = 256;
  private static BufferedImage image = null;
  private static int imageWidth;
  private static int imageHeight;
  private static GUI gui;

  private enum structuringElements {N4, N8, N20}

  private enum edgeDetectionFilters {LAPLACE, COMBINED_SOBEL, KIRSCH}

  private enum smoothingFilters {BOX, GAUSSIAN}

  /**
   * The main method takes an input from the user which is a file name for the 8 bit greyscale image of cells, performs
   * various image processing operations as a pipeline to count the number of cells in the image and displays the image
   * after each operation in the image pipeline using a GUI.
   *
   * @param args String[]; It includes only one argument that is the user input of the file name which is an 8 bit
   *             greyscale image of cells.
   */
  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: java Counter <filename>");
      return;
    }

    String filename = args[0];
    try {
      File file = new File(args[0]);
      image = ImageIO.read(file);

      imageWidth = image.getWidth();
      imageHeight = image.getHeight();

      // Creates and prints the histogram in the console.
      int[] histogramArray = createHistogram(TOTAL_PIXELS);
      for (int i = 0; i < histogramArray.length; i++) {
        System.out.println(i + "=" + histogramArray[i]);
      }

      gui = new GUI();
      gui.addImage("Original image");

      // Image pipeline
      applyWeightedMedianFilter();
      applySmoothing(smoothingFilters.GAUSSIAN);
//      applySmoothing(smoothingFilters.GAUSSIAN);
      applyModifiedAutoContrast(0.65, 0.01);
      applyEdgeDetection(edgeDetectionFilters.COMBINED_SOBEL);
//      applySharpening(2);
      applyWeightedMedianFilter();
      createBinaryImage(65);
      applyClosing(structuringElements.N4);
      applyClosing(structuringElements.N8);
      applyClosing(structuringElements.N20);
      applyClosing(structuringElements.N20);
      applyOpening(structuringElements.N4);
      applyOpening(structuringElements.N8);

      regionLabeling(92);
      gui.setBackground(filename);

//      file = new File(filename + "-output.jpg");
//      ImageIO.write(image, "jpg", file);
    } catch (
      IOException e) {
      e.printStackTrace();
    }

  }

  /**
   * Creates an array for a histogram showing the number of pixels in the available range of intensities.
   *
   * @param bins int; Set the size of the histogram to the given bin.
   * @return int[]; Returns an integer array which consists of the number of pixels in the available range of
   * intensities or as per the size after binning.
   */
  private static int[] createHistogram(int bins) {
    int[] histogramArray = new int[bins];
    for (int y = 0; y < imageHeight; y++) {
      for (int x = 0; x < imageWidth; x++) {
        int intensity = getIntensity(x, y);
        int bin = intensity * bins / TOTAL_PIXELS;
        histogramArray[bin] += 1;
      }
    }
    return histogramArray;
  }

  /**
   * Applies a non linear filter by adding multiple copies of each pixel value according to the weight. It helps to
   * reduce the noise by eliminating the outliers i.e. extreme dark or bright pixels that might influence other
   * filter's result.
   */
  private static void applyWeightedMedianFilter() {
    final int[][] WEIGHTED_MEDIAN_FILTER = new int[FILTER_WIDTH][FILTER_HEIGHT];
    final int WEIGHTED_MEDIAN_FILTER_TOTAL = 15;
    WEIGHTED_MEDIAN_FILTER[0][0] = 1;
    WEIGHTED_MEDIAN_FILTER[0][1] = 2;
    WEIGHTED_MEDIAN_FILTER[0][2] = 1;
    WEIGHTED_MEDIAN_FILTER[1][0] = 2;
    WEIGHTED_MEDIAN_FILTER[1][1] = 3;
    WEIGHTED_MEDIAN_FILTER[1][2] = 2;
    WEIGHTED_MEDIAN_FILTER[2][0] = 1;
    WEIGHTED_MEDIAN_FILTER[2][1] = 2;
    WEIGHTED_MEDIAN_FILTER[2][2] = 1;

    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        int intensity;
        int[] weightedMedianArray = new int[WEIGHTED_MEDIAN_FILTER_TOTAL];
        int weightedMedianCounter = 0;
        for (int j = -1; j <= 1; j++) {
          for (int i = -1; i <= 1; i++) {
            if (isWithinBoundary(x + i, y + j)) {
              intensity = getIntensity(x + i, y + j);
              int filterValue = WEIGHTED_MEDIAN_FILTER[1 + i][1 + j];
              for (int h = 0; h < filterValue; h++) {
                weightedMedianArray[weightedMedianCounter] = intensity;
                weightedMedianCounter++;
              }
            }
          }
        }

        sortArray(weightedMedianArray);
        intensity = getMedian(weightedMedianArray);
        intermediateImage[x][y] = getPixelValue(intensity);
      }
    }

    copyIntermediateToOriginalImage(intermediateImage);
    gui.addImage("Image after weighted median");
  }

  /**
   * Modified auto contrast is a point of operation that modifies the pixels so that they cover the available range of
   * intensities completely while clipping the outliers i.e is the brightest or the darkest pixels.
   *
   * @param sLow  double; The percentage value of the darkest pixels to be clipped.
   * @param sHigh double; The percentage value of the brightest pixels to be clipped.
   */
  private static void applyModifiedAutoContrast(double sLow, double sHigh) {
    final int IMAGE_SIZE = imageWidth * imageHeight;
    int aHatLow = MIN_INTENSITY;
    int aHatHigh = MAX_INTENSITY;
    boolean isAHatHighSet = false;
    boolean isAHatLowSet = false;
    int[] histogramArray = createHistogram(TOTAL_PIXELS);
    int[] cumulativeHistogramArray = createCumulativeHistogram(histogramArray);

    for (int i = 0; i < cumulativeHistogramArray.length; i++) {
      if (!isAHatLowSet && cumulativeHistogramArray[i] >= IMAGE_SIZE * sLow) {
        aHatLow = i;
        isAHatLowSet = true;
      }
      if (!isAHatHighSet && cumulativeHistogramArray[MAX_INTENSITY - i] <= IMAGE_SIZE * (1 - sHigh)) {
        aHatHigh = MAX_INTENSITY - i;
        isAHatHighSet = true;
      }
      if (isAHatLowSet && isAHatHighSet) {
        break;
      }
    }

    for (int y = 0; y < imageHeight; y++) {
      for (int x = 0; x < imageWidth; x++) {
        int intensity = getIntensity(x, y);
        if (intensity <= aHatLow) {
          intensity = MIN_INTENSITY;
        } else if (intensity >= aHatHigh) {
          intensity = MAX_INTENSITY;
        } else {
          intensity = (intensity - aHatLow) * (MAX_INTENSITY / (aHatHigh - aHatLow));
        }
        int pixel = getPixelValue(intensity);
        image.setRGB(x, y, pixel);
      }
    }
    gui.addImage("Image after modified auto contrast");
  }

  /**
   * Creates an array for a cumulative histogram, which is a non decreasing histogram.
   *
   * @param histogramArray integer array; The histogram array for which the cumulative histogram array is to be created.
   * @return int[]; Returns an integer array which consists of the cumulative number of pixels in the available range of
   * intensities.
   */
  private static int[] createCumulativeHistogram(int[] histogramArray) {
    int[] cumulativeHistogramArray = new int[histogramArray.length];
    for (int i = 0; i < histogramArray.length; i++) {
      if (i == 0) {
        cumulativeHistogramArray[i] = histogramArray[i];
      } else {
        cumulativeHistogramArray[i] = histogramArray[i] + cumulativeHistogramArray[i - 1];
      }
    }
    return cumulativeHistogramArray;
  }

  /**
   * Applies the specified smoothing filter.
   *
   * @param sf smoothingFilters (enum); Specifies which smoothing filter is to be applied as available in the smoothing
   *           filters enum.
   */
  private static void applySmoothing(smoothingFilters sf) {
    switch (sf) {
      case BOX:
        applyBoxBlur();
        break;
      case GAUSSIAN:
        applyGaussianBlur();
        break;
    }
  }

  /**
   * Applies the box blur linear filter which is the uniform weighted sum of the pixels in the original pixel's
   * neighbourhood. It makes the image appear fuzzy or blurry.
   */
  private static void applyBoxBlur() {
    int[][] BOX_BLUR_FILTER = new int[FILTER_WIDTH][FILTER_HEIGHT];
    for (int y = 0; y < FILTER_HEIGHT; y++) {
      for (int x = 0; x < FILTER_WIDTH; x++) {
        BOX_BLUR_FILTER[x][y] = 1;
      }
    }

    applySmoothingFilter(FILTER_SIZE, BOX_BLUR_FILTER);
    gui.addImage("Image after box blur");
  }

  /**
   * Applies the gaussian blur linear filter which uses approximation of the normal distribution instead of uniform
   * smoothing. It makes the image less fuzzy than the box blur.
   */
  private static void applyGaussianBlur() {
    final int WEIGHT_SIZE = 40;
    final int[][] GAUSSIAN_BLUR_FILTER = new int[FILTER_WIDTH][FILTER_HEIGHT];
    GAUSSIAN_BLUR_FILTER[0][0] = 3;
    GAUSSIAN_BLUR_FILTER[0][1] = 5;
    GAUSSIAN_BLUR_FILTER[0][2] = 3;
    GAUSSIAN_BLUR_FILTER[1][0] = 5;
    GAUSSIAN_BLUR_FILTER[1][1] = 8;
    GAUSSIAN_BLUR_FILTER[1][2] = 5;
    GAUSSIAN_BLUR_FILTER[2][0] = 3;
    GAUSSIAN_BLUR_FILTER[2][1] = 5;
    GAUSSIAN_BLUR_FILTER[2][2] = 3;

    applySmoothingFilter(WEIGHT_SIZE, GAUSSIAN_BLUR_FILTER);
    gui.addImage("Image after gaussian blur");
  }

  /**
   * Applies the smoothing filter to an intermediate image and copies the intermediate image to the original image as
   * linear filters cannot be applied in place as the pixel values depends upon the neighbouring pixel values.
   *
   * @param weight int; THe weight of the filter. It determines what the filter does.
   * @param filter 2D integer array (int[][]); The filter that is to be used for convolution which smooths the image.
   */
  private static void applySmoothingFilter(int weight, int[][] filter) {
    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        int total = 0;
        for (int j = -1; j <= 1; j++) {
          for (int i = -1; i <= 1; i++) {
            if (isWithinBoundary(x + i, y + j)) {
              int intensity = getIntensity(x + i, y + j);
              total += intensity * filter[1 + i][1 + j];
            }
          }
        }

        int intensity = total / weight;
        intermediateImage[x][y] = getPixelValue(applyClamping(intensity));
      }
    }
    copyIntermediateToOriginalImage(intermediateImage);
  }

  /**
   * A method to copy the intermediate image to the original image, after applying the linear or non liner filter to the
   * intermediate image.
   *
   * @param intermediateImage 2D integer array (int[][]); The intermediate image that is to be copied to the original
   *                          image.
   */
  private static void copyIntermediateToOriginalImage(int[][] intermediateImage) {
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        image.setRGB(x, y, intermediateImage[x][y]);
      }
    }
  }

  /**
   * Returns the pixel value after applying the greyscale or binary intensity to the RGB components.
   *
   * @param intensity int; The intensity value to be applied to the RGB components.
   * @return int; Returns the integer pixel value after applying the greyscale or the binary intensity to the RGB
   * components.
   */
  private static int getPixelValue(int intensity) {
    return ((intensity & 0xFF) << 16) | ((intensity & 0xFF) << 8) | (intensity & 0xFF);
  }

  /**
   * Gets the intensity value of the 8 bit greyscale image;
   *
   * @param x int; X coordinate of the image.
   * @param y int; X coordinate of the image.
   * @return int; Returns the integer intensity value of the greyscale image.
   */
  private static int getIntensity(int x, int y) {
    int pixel = image.getRGB(x, y);
    return (pixel & 0x0000ff);
  }

  /**
   * Checks whether the provided x and y coordinates are within the boundary of the image;
   *
   * @param x int; X coordinate of the image.
   * @param y int; X coordinate of the image.
   * @return boolean; Returns true is the coordinates are within the boundary of the image, otherwise false.
   */
  private static boolean isWithinBoundary(int x, int y) {
    return (x >= 0 && x < imageWidth) && (y >= 0 && y < imageHeight);
  }

  /**
   * This method enhances the edges of fuzzy or blurry image by subtraction a fraction of the second order derivative
   * from the original image.
   *
   * @param weight int; The weight to be applied for sharpening the image.
   */
  private static void applySharpening(int weight) {
    int[][] positiveFilter = new int[FILTER_WIDTH][FILTER_HEIGHT];
    int[][] negativeFilter = new int[FILTER_WIDTH][FILTER_HEIGHT];
    positiveFilter[1][0] = 1;
    positiveFilter[0][1] = 1;
    positiveFilter[2][1] = 1;
    positiveFilter[1][2] = 1;
    negativeFilter[1][1] = 4;

    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int y = 0; y < imageHeight; y++) {
      for (int x = 0; x < imageWidth; x++) {
        int total = 0;
        for (int j = -1; j <= 1; j++) {
          for (int i = -1; i <= 1; i++) {
            if (isWithinBoundary(x + i, y + j)) {
              int intensity = getIntensity(x + i, y + j);
              total += (intensity * positiveFilter[1 + i][1 + j]) - (intensity * negativeFilter[1 + i][1 + j]);
            }
          }
        }
        int intensity = getIntensity(x, y);
        intensity = intensity - (total / weight);
        intermediateImage[x][y] = getPixelValue(applyClamping(intensity));
      }
    }

    copyIntermediateToOriginalImage(intermediateImage);
  }

  /**
   * This method checks if the pixel value exceed the range of intensity and sets it within the peak of the top or
   * bottom of the range  that is which ever is the closest.
   *
   * @param intensity int; The intensity value of the pixel.
   * @return int; Returns the pixel value after checking if it exceeds the range of intensity and sets it within the
   * peak of the top or bottom of the range if the value exceeds the range.
   */
  private static int applyClamping(int intensity) {
    if (intensity < MIN_INTENSITY) {
      intensity = MIN_INTENSITY;
    }
    if (intensity > MAX_INTENSITY) {
      intensity = MAX_INTENSITY;
    }
    return intensity;
  }

  /**
   * Applies the specified edge detection filter as specified in the argument.
   *
   * @param edf edgeDetectionFilters (enum); Specifies which edge detection filter is to be applied as available in the
   *            edge detection filters enum.
   */
  private static void applyEdgeDetection(edgeDetectionFilters edf) {
    switch (edf) {
      case COMBINED_SOBEL:
        applyCombinedSobelFilters();
        break;
      case KIRSCH:
        applyKirschFilters();
        break;
      case LAPLACE:
        applyLaplaceFilter();
    }
  }

  /**
   * This filter helps to detect the edge by estimating the gradient magnitude of the edge using the Sobel horizontal
   * partial derivative filter (Sobel X) and Sobel vertical partial derivative filter (Sobel Y).
   */
  private static void applyCombinedSobelFilters() {
    int[] filter = new int[3];
    filter[0] = 1;
    filter[1] = 2;
    filter[2] = 1;

    int[][] positiveFilterX = new int[FILTER_WIDTH][FILTER_HEIGHT];
    for (int y = 0; y < FILTER_HEIGHT; y++) {
      positiveFilterX[0][y] = filter[y];
    }

    int[][] negativeFilterX = new int[FILTER_WIDTH][FILTER_HEIGHT];
    for (int y = 0; y < FILTER_HEIGHT; y++) {
      negativeFilterX[FILTER_WIDTH - 1][y] = filter[y];
    }

    int[][] positiveFilterY = new int[FILTER_WIDTH][FILTER_HEIGHT];
    for (int x = 0; x < FILTER_WIDTH; x++) {
      positiveFilterY[x][0] = filter[x];
    }

    int[][] negativeFilterY = new int[FILTER_WIDTH][FILTER_HEIGHT];
    for (int x = 0; x < FILTER_WIDTH; x++) {
      negativeFilterY[x][FILTER_HEIGHT - 1] = filter[x];
    }

    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int y = 0; y < imageHeight; y++) {
      for (int x = 0; x < imageWidth; x++) {
        int sobelX = 0;
        int sobelY = 0;
        for (int j = -1; j <= 1; j++) {
          for (int i = -1; i <= 1; i++) {
            if (isWithinBoundary(x + i, y + j)) {
              int intensity = getIntensity(x + i, y + j);
              sobelX += (intensity * positiveFilterX[1 + i][1 + j]) - (intensity * negativeFilterX[1 + i][1 + j]);
              sobelY += (intensity * positiveFilterY[1 + i][1 + j]) - (intensity * negativeFilterY[1 + i][1 + j]);
            }
          }
          int result = (int) Math.sqrt(Math.pow(sobelX, 2) + Math.pow(sobelY, 2));
          intermediateImage[x][y] = getPixelValue(applyClamping(result));
        }
      }
    }

    copyIntermediateToOriginalImage(intermediateImage);
    gui.addImage("Image after combined Sobel filters");
  }

  /**
   * This filter helps to detect not only the vertical and horizontal edges but also the edges at 45 degree angle. It
   * uses eight different filters but some of the filters produce the same result as others with the result differing
   * only in sign, so we can reduce the number of filters to just four. The gradient magnitude is the output of the
   * filter that gives the maximum absolute value compared to other filters.
   */
  private static void applyKirschFilters() {
    int[][] positiveFilter1 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    positiveFilter1[FILTER_WIDTH - 1][0] = 1;
    positiveFilter1[FILTER_WIDTH - 1][1] = 2;
    positiveFilter1[FILTER_WIDTH - 1][2] = 1;
    int[][] negativeFilter1 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    negativeFilter1[0][0] = 1;
    negativeFilter1[0][1] = 2;
    negativeFilter1[0][2] = 1;
    int[][] positiveFilter2 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    positiveFilter2[FILTER_WIDTH - 1][1] = 1;
    positiveFilter2[FILTER_WIDTH - 1][2] = 2;
    positiveFilter2[1][2] = 1;
    int[][] negativeFilter2 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    negativeFilter2[0][0] = 2;
    negativeFilter2[1][0] = 1;
    negativeFilter2[0][1] = 1;
    int[][] positiveFilter3 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    positiveFilter3[0][FILTER_HEIGHT - 1] = 1;
    positiveFilter3[1][FILTER_HEIGHT - 1] = 2;
    positiveFilter3[2][FILTER_HEIGHT - 1] = 1;
    int[][] negativeFilter3 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    negativeFilter3[0][0] = 1;
    negativeFilter3[1][0] = 2;
    negativeFilter3[2][0] = 1;
    int[][] positiveFilter4 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    positiveFilter4[0][1] = 1;
    positiveFilter4[0][2] = 2;
    positiveFilter4[1][2] = 1;
    int[][] negativeFilter4 = new int[FILTER_WIDTH][FILTER_HEIGHT];
    negativeFilter4[FILTER_WIDTH - 1][0] = 2;
    negativeFilter4[FILTER_WIDTH - 1][1] = 1;
    negativeFilter4[1][0] = 1;

    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int y = 0; y < imageHeight; y++) {
      for (int x = 0; x < imageWidth; x++) {
        int total1 = 0;
        int total2 = 0;
        int total3 = 0;
        int total4 = 0;
        int[] results = new int[4];

        for (int j = -1; j <= 1; j++) {
          for (int i = -1; i <= 1; i++) {
            if (isWithinBoundary(x + i, y + j)) {
              int intensity = getIntensity(x + i, y + j);
              total1 += Math.abs(intensity * positiveFilter1[1 + i][1 + j]) - (intensity * negativeFilter1[1 + i][1 + j]);
              total2 += Math.abs(intensity * positiveFilter2[1 + i][1 + j]) - (intensity * negativeFilter2[1 + i][1 + j]);
              total3 += Math.abs(intensity * positiveFilter3[1 + i][1 + j]) - (intensity * negativeFilter3[1 + i][1 + j]);
              total4 += Math.abs(intensity * positiveFilter4[1 + i][1 + j]) - (intensity * negativeFilter4[1 + i][1 + j]);
            }
          }
        }
        results[0] = total1;
        results[1] = total2;
        results[2] = total3;
        results[3] = total4;
        int result = getMaxValue(results);
        intermediateImage[x][y] = getPixelValue(applyClamping(result));
      }
    }

    copyIntermediateToOriginalImage(intermediateImage);
    gui.addImage("Image after Kirsch filters");
  }

  /**
   * The Laplace filter can also be used to detect the edges. The Laplace filter smooths out the intensity changes where
   * there are positive coefficients and the filter reacts strongly to the intensity changes where thare are negative
   * coefficients.
   */
  private static void applyLaplaceFilter() {
    int[][] positiveFilter = new int[FILTER_WIDTH][FILTER_HEIGHT];
    positiveFilter[1][1] = 8;
    int[][] negativeFilter = new int[FILTER_WIDTH][FILTER_HEIGHT];
    for (int y = 0; y < FILTER_HEIGHT; y++) {
      for (int x = 0; x < FILTER_WIDTH; x++) {
        negativeFilter[x][y] = 1;
      }
    }
    negativeFilter[1][1] = 0;

    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int y = 0; y < imageHeight; y++) {
      for (int x = 0; x < imageWidth; x++) {
        int total = 0;
        for (int j = -1; j <= 1; j++) {
          for (int i = -1; i <= 1; i++) {
            if (isWithinBoundary(x + i, y + j)) {
              int intensity = getIntensity(x + i, y + j);
              total += (intensity * positiveFilter[1 + i][1 + j]) - (intensity * negativeFilter[1 + i][1 + j]);
            }
          }
        }
        intermediateImage[x][y] = getPixelValue(applyClamping(total));
      }
    }

    copyIntermediateToOriginalImage(intermediateImage);
    gui.addImage("Image after Laplace filter");
  }

  /**
   * This method can be used to find out the maximum value in an array of integers.
   *
   * @param array int[]; An array of integers.
   * @return int; Returns an integer value which is the maximum value found in the given array.
   */
  private static int getMaxValue(int[] array) {
    int maxValue = array[0];
    for (int i = 1; i < array.length; i++) {
      if (array[i] > maxValue) {
        maxValue = array[i];
      }
    }
    return maxValue;
  }

  /**
   * This method creates a binary image by thresholding where the background pixel is changed into white and the
   * foreground pixel is changed into black.
   *
   * @param threshold int;
   */
  private static void createBinaryImage(int threshold) {
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        int intensity = getIntensity(x, y);
        if (intensity < threshold) {
          intensity = MAX_INTENSITY;
        } else {
          intensity = MIN_INTENSITY;
        }

        int pixel = getPixelValue(intensity);
        image.setRGB(x, y, pixel);
      }
    }
    gui.addImage("Image after threshold");
  }

  /**
   * In opening, erosion (shrinking) is followed by dialation (growing) of the blobs. It helps to eliminate the small
   * structures and grow the remaining structure.
   *
   * @param n structuringElements (enum); It is a pixel neighbourhood of different sizes as available in the
   *          structuringElements enum which is used for shrinking or growing;
   */
  private static void applyOpening(structuringElements n) {
    applyMorphing(n, MAX_INTENSITY, MIN_INTENSITY);
    gui.addImage("Image after opening (" + n + ")");
  }

  /**
   * In closing, dialation (growing) is followed by erosion (shrinking) of the blobs. It helps to eliminate the small
   * holes and fissures in the foreground.
   *
   * @param n structuringElements (enum); It is a pixel neighbourhood of different sizes as available in the
   *          structuringElements enum which is used for shrinking or growing;
   */
  private static void applyClosing(structuringElements n) {
    applyMorphing(n, MIN_INTENSITY, MAX_INTENSITY);
    gui.addImage("Image after closing (" + n + ")");
  }

  /**
   * This method applies the morphing operations.
   *
   * @param n     structuringElements (enum); It is a pixel neighbourhood of different sizes as available in the
   *              structuringElements enum which is used for shrinking or growing;
   * @param start int; The integer value which is the value of the background or foreground that decides whether erosion
   *              or dialation is to be performed first.
   * @param end   int; The integer value which is the value of the background or foreground that decides whether erosion
   *              or dialation is to be performed after performing the dialation or erosion operation.
   */
  private static void applyMorphing(structuringElements n, int start, int end) {
    int[][] filter = new int[FILTER_WIDTH][FILTER_HEIGHT];
    switch (n) {
      case N4:
        filter[1][0] = 1;
        filter[0][1] = 1;
        filter[1][1] = 1;
        filter[2][1] = 1;
        filter[1][2] = 1;
        break;
      case N8:
        for (int y = 0; y < FILTER_HEIGHT; y++) {
          for (int x = 0; x < FILTER_WIDTH; x++) {
            filter[x][y] = 1;
          }
        }
        break;
      case N20:
        filter = new int[5][5];
        for (int y = 0; y < filter[0].length; y++) {
          for (int x = 0; x < filter.length; x++) {
            filter[x][y] = 1;
          }
        }
        filter[0][0] = 0;
        filter[4][0] = 0;
        filter[0][4] = 0;
        filter[4][4] = 0;
        break;
    }

    int[][] intermediateImage1 = new int[imageWidth][imageHeight];
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        int intensity = getIntensity(x, y);
        intermediateImage1[x][y] = intensity;
        if (intensity == start) {
          for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
              if (isWithinBoundary(x + i, y + j)) {
                intensity = getIntensity(x + i, y + j);

                if (filter[1 + i][1 + j] == 1 && intensity == end) {
                  intermediateImage1[x + i][y + j] = start;
                }
              }
            }
          }
        }
      }
    }

    int[][] intermediateImage2 = new int[imageWidth][imageHeight];
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        int intensity = intermediateImage1[x][y];
        intermediateImage2[x][y] = intensity;
        if (intensity == end) {
          for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
              if (isWithinBoundary(x + i, y + j)) {
                intensity = intermediateImage1[x + i][y + j];

                if (filter[1 + i][1 + j] == 1 && intensity == start) {
                  intermediateImage2[x + i][y + j] = end;
                }
              }
            }
          }
        }
        intermediateImage2[x][y] = getPixelValue(intermediateImage2[x][y]);
      }
    }
    copyIntermediateToOriginalImage(intermediateImage2);
  }

  /**
   * Region labeling is used to label and identify the blobs or cells after detecting them. Then it is used to count the
   * number of distinct cells it has identified.
   *
   * @param minBlobSize int; The minimum size of the blob that is acceptable to be considered as a cell.
   */
  private static void regionLabeling(int minBlobSize) {
    int label = 2;
    int count = 0;
    int[][] intermediateImage = new int[imageWidth][imageHeight];
    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        intermediateImage[x][y] = getPixelValue(BACKGROUND_PIXEL_VALUE);
      }
    }

    for (int x = 0; x < imageWidth; x++) {
      for (int y = 0; y < imageHeight; y++) {
        int intensity = getIntensity(x, y);
        if (intensity == MIN_INTENSITY) {
          if (floodFill(x, y, label, intermediateImage, minBlobSize)) {
            label += 1;
            count++;
          }
        }
      }
    }

    copyIntermediateToOriginalImage(intermediateImage);

    System.out.println("Total cells found = " + count);
    gui.addImage("Total cells found = " + count + ". Image after region labeling and removing small structures");
  }

  /**
   * This method fills up the blob with a given label in order to identify it as a distinct cell.
   *
   * @param x                 int; The X coordinate integer value of the blob.
   * @param y                 int; The Y coordinate integer value of the blob.
   * @param label             int; The integer value that the blob is to be filled with to represent it as a distinct blob or cell.
   * @param intermediateImage int[][]; The intermediate image that is to be copied to the original image after labeling
   *                          in order to set the original image back to the binary image instead of using the labels as
   *                          the pixel value.
   * @return boolean; Returns true if the blob is greater or equal to the minimum acceptable size, otherwise false.
   */
  private static boolean floodFill(int x, int y, int label, int[][] intermediateImage, int minBlobSize) {
    Stack stack = new Stack();
    stack.push(x, y);
    Stack.Node currentNode = stack.head;
    int count = 0;
    while (currentNode != null) {
      Stack.Node coordinates = stack.pop();
      if (isWithinBoundary(coordinates.x, coordinates.y)) {
        int intensity = getIntensity(coordinates.x, coordinates.y);
        if (intensity == MIN_INTENSITY) {
          intensity = FOREGROUND_LABEL;
        }

        if (intensity == FOREGROUND_LABEL) {
          intermediateImage[coordinates.x][coordinates.y] = FOREGROUND_PIXEL_VALUE;
          int pixel = getPixelValue(label);
          addNeighboursToStack(stack, coordinates, pixel);
          count++;
        }
      }
      currentNode = stack.head;
    }

    // Remove the blob if it is smaller then the given threshold.
    if (count < minBlobSize) {
      removeBlob(x, y, label, intermediateImage);
      return false;
    }
    return true;
  }

  /**
   * Removes the unwanted small structures or blobs that are smaller than a given threshold value and that remain after
   * performing the various image processing operations.
   *
   * @param x                 int; The X coordinate integer value of the blob that is to be removed.
   * @param y                 int; The Y coordinate integer value of the blob that is to be removed.
   * @param label             int; The integer label value of the blob that is to be remove.
   * @param intermediateImage int[][]; The intermediate image that is to be copied to the original image after labeling
   *                          and removing the unwanted blobs tthat are smaller than a given threshold in order to set
   *                          the original image back to the binary image instead of using the labels as the pixel value.
   */
  private static void removeBlob(int x, int y, int label, int[][] intermediateImage) {
    Stack stack = new Stack();
    stack.push(x, y);
    Stack.Node currentNode = stack.head;
    while (currentNode != null) {
      Stack.Node coordinates = stack.pop();
      if (isWithinBoundary(coordinates.x, coordinates.y)) {
        int intensity = getIntensity(coordinates.x, coordinates.y);
        if (intensity == label) {
          intermediateImage[coordinates.x][coordinates.y] = BACKGROUND_PIXEL_VALUE;
          addNeighboursToStack(stack, coordinates, BACKGROUND_PIXEL_VALUE);
        }
      }
      currentNode = stack.head;
    }
  }

  /**
   * Adds the neighbouring pixels to the stack while performing flood fill or removal of blobs smaller than a given
   * threshold.
   *
   * @param stack       Stack (object); A list of coordinates of the pixels arranged in Last in First Out order.
   * @param coordinates Stack.Node (object); The node object of the stack that stores the coordinate values of a given
   *                    pixel.
   * @param pixel       int; The integer pixel value.
   */
  private static void addNeighboursToStack(Stack stack, Stack.Node coordinates, int pixel) {
    image.setRGB(coordinates.x, coordinates.y, pixel);
    stack.push(coordinates.x + 1, coordinates.y);
    stack.push(coordinates.x, coordinates.y + 1);
    stack.push(coordinates.x + 1, coordinates.y);
    stack.push(coordinates.x, coordinates.y - 1);
    stack.push(coordinates.x - 1, coordinates.y);
  }

  /**
   * This method sorts the given array in an ascending order
   *
   * @param array int[]; Integer array that is to be sorted.
   */
  private static void sortArray(int[] array) {
    for (int i = 0; i < array.length; i++) {
      for (int j = 0; j < array.length - 1; j++) {
        if (array[j] > array[j + 1]) {
          int temp = array[j];
          array[j] = array[j + 1];
          array[j + 1] = temp;
        }
      }
    }
  }

  /**
   * This method helps to find the median value of a given array.
   *
   * @param array int[]; The integer array whose median value is to be found.
   * @return int; Returns the integer median value of an array.
   */
  private static int getMedian(int[] array) {
    int arrayLength = array.length;
    int median;
    if (arrayLength % 2 == 0) {
      // if number of items are even
      median = (array[(arrayLength - 1) / 2] + array[arrayLength / 2]) / 2;
    } else {
      // if number of items are odd
      median = array[arrayLength / 2];
    }

    return median;
  }

  /**
   * Creates a Graphical User Interface (GUI) for the cell counter program that displays image after each operation in
   * the image processing pipeline.
   */
  private static class GUI extends JFrame {
    JPanel background;

    /**
     * Constructor for the GUI class that creates a GUI by extending JFrame, creates a background using JPanel and sets
     * its layout.
     */
    private GUI() {
      background = new JPanel();
      background.setLayout(new BoxLayout(background, BoxLayout.Y_AXIS));
    }

    /**
     * Sets the background for the cell counter GUI.
     *
     * @param filename String; The name of the file which is a user input, that is displayed in the title of the JFrame.
     */
    private void setBackground(String filename) {
      final int WIDTH_OFFSET = 20;
      final int HEIGHT_OFFSET = 300;
      setTitle("Counter GUI for " + filename);
      setResizable(false);
      setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
      setVisible(true);
      JScrollPane scrollPane = new JScrollPane();
      scrollPane.setPreferredSize(new Dimension(imageWidth + WIDTH_OFFSET, imageHeight + HEIGHT_OFFSET));
      scrollPane.setViewportView(background);
      add(scrollPane);
      pack();
    }

    /**
     * Adds image to the background of the GUI.
     *
     * @param label String. Label for each operation in the image processing pipeline that is to be displayed.
     */
    private void addImage(String label) {
      final int LEFT_OFFSET = 10;
      final int TOP_OFFSET = 10;
      final int BOTTOM_OFFSET = 5;
      background.add(Box.createRigidArea(new Dimension(0, TOP_OFFSET)));
      JLabel imageLabel = new JLabel(label + ":");
      imageLabel.setBorder(new EmptyBorder(0, LEFT_OFFSET, 0, 0));
      background.add(imageLabel);
      background.add(Box.createRigidArea(new Dimension(0, BOTTOM_OFFSET)));
      ImageIcon imageIcon = new ImageIcon();
      JLabel jLabel = new JLabel();
      imageIcon.setImage(copyImage(image));
      jLabel.setIcon(imageIcon);
      background.add(jLabel);
    }

    /**
     * Creates a copy of the image after each operation in the image processing pipeline to display the image after
     * performing those operations.
     *
     * @param image BufferedImage; The image that is to be copied.
     * @return BufferedImage; Returns a reference of the copy image.
     */
    public static BufferedImage copyImage(BufferedImage image) {
      BufferedImage copyImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
      Graphics g = copyImage.createGraphics();
      g.drawImage(image, 0, 0, null);
      return copyImage;
    }
  }

  /**
   * A dynamic linked list that is in Last in First Out (LIFO) order.
   */
  public static class Stack {
    /**
     * The Node of the stack holds the x and y coordinates of a pixel of the image and a reference to the pixel in the
     * stack.
     */
    private class Node {
      private int x;
      private int y;
      private Node next;

      /**
       * The constructor of the Node class that sets the x and y coordinate values of a pixel to the node.
       *
       * @param x int; X coordinate of the pixel;
       * @param y int; Y coordinate of the pixel;
       */
      private Node(int x, int y) {
        this.x = x;
        this.y = y;
      }
    }

    /**
     * The top of the stack;
     */
    private Node head;

    /**
     * Adds the x and y coordinate values of a pixel to the stack at the top.
     *
     * @param x int; X coordinate of the pixel;
     * @param y int; Y coordinate of the pixel;
     */
    public void push(int x, int y) {
      Node tmpNode = new Node(x, y);
      tmpNode.next = head;
      head = tmpNode;
    }

    /**
     * Removes the x and y coordinate values of a pixel stored in a node at the top of the stack.
     *
     * @return Node (Object); Returns the node with x and y coordinate values of a pixel that has been removed from the
     * top of the stack.
     */
    public Node pop() {
      if (isEmpty()) {
        return null;
      }

      Node tmpClient = head;
      head = tmpClient.next;
      tmpClient.next = null;
      return tmpClient;
    }

    /**
     * Checks if the stack is empty.
     *
     * @return boolean; Returns true if the stack is empty, otherwise false.
     */
    public boolean isEmpty() {
      return head == null;
    }
  }

}