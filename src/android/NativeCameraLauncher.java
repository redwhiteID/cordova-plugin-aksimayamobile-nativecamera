/*
	    Copyright 2014 Giovanni Di Gregorio.

		Licensed under the Apache License, Version 2.0 (the "License");
		you may not use this file except in compliance with the License.
		You may obtain a copy of the License at

		http://www.apache.org/licenses/LICENSE-2.0

		Unless required by applicable law or agreed to in writing, software
		distributed under the License is distributed on an "AS IS" BASIS,
		WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
		See the License for the specific language governing permissions and
   		limitations under the License.   			
 */

package com.aksimayamobile.nativecamera;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.apache.cordova.ExifHelper;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

/**
 * This class launches the camera view, allows the user to take a picture,
 * closes the camera view, and returns the captured image. When the camera view
 * is closed, the screen displayed before the camera view was shown is
 * redisplayed.
 */
public class NativeCameraLauncher extends CordovaPlugin {

	private static final String LOG_TAG = "NativeCameraLauncher";

	private int mQuality;
	private int targetWidth;
	private int targetHeight;
	private int destinationType;
	private Uri imageUri;
	private File photo;
	private int numPics;
	private static final String _DATA = "_data";
	private CallbackContext callbackContext;

	public NativeCameraLauncher() {
	}

	void failPicture(String reason) {
		callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, reason));
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		PluginResult.Status status = PluginResult.Status.OK;
		String result = "";
		this.callbackContext = callbackContext;
		try {
			if (action.equals("takePicture")) {
				this.targetHeight = 0;
				this.targetWidth = 0;
				this.mQuality = 80;
				this.targetHeight = args.getInt(4);
				this.targetWidth = args.getInt(3);
				this.destinationType = args.getInt(1);
				this.mQuality = args.getInt(0);
				this.takePicture();
				PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
				r.setKeepCallback(true);
				callbackContext.sendPluginResult(r);
				return true;
			}
			return false;
		} catch (JSONException e) {
			e.printStackTrace();
			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
			return true;
		}
	}

	public void takePicture() {
		// Save the number of images currently on disk for later
		this.numPics = queryImgDB().getCount();
		Intent intent = new Intent(this.cordova.getActivity().getApplicationContext(), CameraActivity.class);
		this.photo = createCaptureFile();
		this.imageUri = Uri.fromFile(photo);
		intent.putExtra(MediaStore.EXTRA_OUTPUT, this.imageUri);
		this.cordova.startActivityForResult((CordovaPlugin) this, intent, 1);
	}

	private File createCaptureFile() {
		File photo = new File(getTempDirectoryPath(this.cordova.getActivity().getApplicationContext()), "Pic.jpg");
		return photo;
	}

	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		// If image available
		if (resultCode == Activity.RESULT_OK) {
			int rotate = 0;
			try {
				// Create an ExifHelper to save the exif data that is lost
				// during compression
				ExifHelper exif = new ExifHelper();
				exif.createInFile(getTempDirectoryPath(this.cordova.getActivity().getApplicationContext())
						+ "/Pic.jpg");
				exif.readExifData();
				rotate = exif.getOrientation();

				// Read in bitmap of captured image
				Bitmap bitmap;
				try {
					bitmap = android.provider.MediaStore.Images.Media
							.getBitmap(this.cordova.getActivity().getContentResolver(), imageUri);
				} catch (FileNotFoundException e) {
					Uri uri = intent.getData();
					android.content.ContentResolver resolver = this.cordova.getActivity().getContentResolver();
					bitmap = android.graphics.BitmapFactory
							.decodeStream(resolver.openInputStream(uri));
				}

				// If bitmap cannot be decoded, this may return null
				if (bitmap == null) {
					this.failPicture("Error decoding image.");
					return;
				}

				bitmap = scaleBitmap(bitmap);

				// Create entry in media store for image
				// (Don't use insertImage() because it uses default compression
				// setting of 50 - no way to change it)
				ContentValues values = new ContentValues();
				values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,
						"image/jpeg");
				Uri uri = null;
				try {
					uri = this.cordova.getActivity().getContentResolver()
							.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
									values);
				} catch (UnsupportedOperationException e) {
					LOG.d(LOG_TAG, "Can't write to external media storage.");
					try {
						uri = this.cordova.getActivity().getContentResolver()
								.insert(android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI,
										values);
					} catch (UnsupportedOperationException ex) {
						LOG.d(LOG_TAG, "Can't write to internal media storage.");
						this.failPicture("Error capturing image - no media storage found.");
						return;
					}
				}

				// Get real path
				String realPath = getRealPathFromURI(uri, this.cordova);

				// Create directories
				File outputFile = new File(realPath);
				outputFile.getParentFile().mkdirs();

				// Add compressed version of captured image to returned media
				// store Uri
				bitmap = getRotatedBitmap(rotate, bitmap, exif);
				Log.i(LOG_TAG, "URI: " + uri.toString());
				OutputStream os = this.cordova.getActivity().getContentResolver()
						.openOutputStream(uri);
				bitmap.compress(Bitmap.CompressFormat.JPEG, this.mQuality, os);
				os.close();

				// Restore exif data to file
				exif.createOutFile(realPath);
				exif.writeExifData();

				// Send Uri back to JavaScript for viewing image
				//this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, uri.toString()));

				android.util.Log.i("CameraPlugin", "destinationType: " + this.destinationType);
				if (this.destinationType == 1) { //File URI
					// Send Uri back to JavaScript for viewing image
					this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, uri.toString()));
				} else if (this.destinationType == 0) { //DATA URL
					String base64Data = NativeCameraLauncher.encodeTobase64(bitmap, this.mQuality);
					this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, base64Data));
				}
				else {
					this.failPicture("Unsupported destination");
				}

				bitmap.recycle();
				bitmap = null;
				System.gc();

				checkForDuplicateImage();
			} catch (IOException e) {
				e.printStackTrace();
				this.failPicture("Error capturing image.");
			}
		}

		// If cancelled
		else if (resultCode == Activity.RESULT_CANCELED) {
			this.failPicture("Camera cancelled.");
		}

		// If something else
		else {
			this.failPicture("Did not complete!");
		}
	}
	public static String encodeTobase64(Bitmap image, int quality)
	{
	    ByteArrayOutputStream baos = new ByteArrayOutputStream();  
	    image.compress(Bitmap.CompressFormat.JPEG, quality, baos);
	    byte[] b = baos.toByteArray();
	    String imageEncoded = Base64.encodeToString(b, Base64.DEFAULT);
	    return imageEncoded;
	}
	public Bitmap scaleBitmap(Bitmap bitmap) {
		int newWidth = this.targetWidth;
		int newHeight = this.targetHeight;
		int origWidth = bitmap.getWidth();
		int origHeight = bitmap.getHeight();

		// If no new width or height were specified return the original bitmap
		if (newWidth <= 0 && newHeight <= 0) {
			return bitmap;
		}
		// Only the width was specified
		else if (newWidth > 0 && newHeight <= 0) {
			newHeight = (newWidth * origHeight) / origWidth;
		}
		// only the height was specified
		else if (newWidth <= 0 && newHeight > 0) {
			newWidth = (newHeight * origWidth) / origHeight;
		}
		// If the user specified both a positive width and height
		// (potentially different aspect ratio) then the width or height is
		// scaled so that the image fits while maintaining aspect ratio.
		// Alternatively, the specified width and height could have been
		// kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
		// would result in whitespace in the new image.
		else {
			double newRatio = newWidth / (double) newHeight;
			double origRatio = origWidth / (double) origHeight;

			if (origRatio > newRatio) {
				newHeight = (newWidth * origHeight) / origWidth;
			} else if (origRatio < newRatio) {
				newWidth = (newHeight * origWidth) / origHeight;
			}
		}

		return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
	}
	
	private Bitmap getRotatedBitmap(int rotate, Bitmap bitmap, ExifHelper exif) {
        Matrix matrix = new Matrix();
        matrix.setRotate(rotate);
        try
        {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            exif.resetOrientation();
        }
        catch (OutOfMemoryError oom)
        {
            // You can run out of memory if the image is very large:
            // http://simonmacdonald.blogspot.ca/2012/07/change-to-camera-code-in-phonegap-190.html
            // If this happens, simply do not rotate the image and return it unmodified.
            // If you do not catch the OutOfMemoryError, the Android app crashes.
        }
        return bitmap;
    }

	private Cursor queryImgDB() {
		return this.cordova.getActivity().getContentResolver().query(
				android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
				new String[] { MediaStore.Images.Media._ID }, null, null, null);
	}

	private void checkForDuplicateImage() {
		int diff = 2;
		Cursor cursor = queryImgDB();
		int currentNumOfImages = cursor.getCount();

		// delete the duplicate file if the difference is 2 for file URI or 1
		// for Data URL
		if ((currentNumOfImages - numPics) == diff) {
			cursor.moveToLast();
			int id = Integer.valueOf(cursor.getString(cursor
					.getColumnIndex(MediaStore.Images.Media._ID))) - 1;
			Uri uri = Uri.parse(MediaStore.Images.Media.EXTERNAL_CONTENT_URI
					+ "/" + id);
			this.cordova.getActivity().getContentResolver().delete(uri, null, null);
		}
	}

	private String getTempDirectoryPath(Context ctx) {
		File cache = null;

		// SD Card Mounted
		if (Environment.getExternalStorageState().equals(
				Environment.MEDIA_MOUNTED)) {
			cache = new File(Environment.getExternalStorageDirectory()
					.getAbsolutePath()
					+ "/Android/data/"
					+ ctx.getPackageName() + "/cache/");
		}
		// Use internal storage
		else {
			cache = ctx.getCacheDir();
		}

		// Create the cache directory if it doesn't exist
		if (!cache.exists()) {
			cache.mkdirs();
		}

		return cache.getAbsolutePath();
	}

	private String getRealPathFromURI(Uri contentUri, CordovaInterface ctx) {
		String[] proj = { _DATA };
		Cursor cursor = cordova.getActivity().managedQuery(contentUri, proj, null, null, null);
		int column_index = cursor.getColumnIndexOrThrow(_DATA);
		cursor.moveToFirst();
		return cursor.getString(column_index);
	}
}
