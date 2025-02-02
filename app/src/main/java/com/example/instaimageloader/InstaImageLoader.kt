package com.example.instaimageloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.File
import java.io.FileOutputStream

class InstaImageLoader private constructor(
    private val context: Context,
    private val placeholder: Int?,
    private val errorImage: Int?,
) {

    private val client = OkHttpClient()  // For network requests
    private val cacheDir = File(context.cacheDir, "image_cache")  // Disk cache location
    private val memoryCache: LruCache<String, Bitmap> = LruCache(10 * 1024 * 1024)  // 10MB memory cache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Ensure cache directory exists
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    fun loadImage(url: String, imageView: ImageView) {
        // Set the placeholder image if provided
        placeholder?.let {
            imageView.setImageResource(it)
        }

        val fileName = generateCacheFileName(url)

        // First, check if the image is in memory cache
        memoryCache.get(fileName)?.let {
            imageView.setImageBitmap(it)
            cancelScope()  // Cancel scope after immediate image load from memory cache
            return
        }

        // Then, check if the image is in disk cache
        val cachedFile = File(cacheDir, fileName)
        if (cachedFile.exists()) {
            loadImageFromDiskCache(cachedFile, fileName, imageView)
            cancelScope()  // Cancel scope after image load from disk cache
            return
        }

        // If not found in cache, download the image
        scope.launch {
            downloadImageAndCache(url, fileName, imageView)
        }
    }

    private fun generateCacheFileName(url: String): String = url.hashCode().toString()

    private fun loadImageFromDiskCache(cachedFile: File, fileName: String, imageView: ImageView) {
        val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
        imageView.setImageBitmap(bitmap)
        memoryCache.put(fileName, bitmap) // Cache in memory
    }

    private suspend fun downloadImageAndCache(url: String, fileName: String, imageView: ImageView) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                fetchImageFromNetwork(url)
            }

            if (bitmap != null) {
                // On success, update UI and cache image
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bitmap)
                }
                saveImageToCache(fileName, bitmap)
            } else {
                handleError(imageView)
            }
        } catch (e: IOException) {
            // Handle network error
            e.printStackTrace()
            handleError(imageView)
        } catch (e: Exception) {
            // Handle other errors
            e.printStackTrace()
            handleError(imageView)
        } finally {
            cancelScope()  // Always cancel the scope after finishing the task
        }
    }

    private suspend fun handleError(imageView: ImageView) {
        withContext(Dispatchers.Main) {
            imageView.setImageResource(errorImage ?: android.R.drawable.stat_notify_error)
        }
    }

    private fun fetchImageFromNetwork(url: String): Bitmap? {
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            response.body?.byteStream()?.let { inputStream ->
                // Handle large images (downsampling if necessary)
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun saveImageToCache(fileName: String, bitmap: Bitmap) {
        // Save to disk cache only if the file doesn't already exist
        val cachedFile = File(cacheDir, fileName)
        if (!cachedFile.exists()) {
            try {
                FileOutputStream(cachedFile).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        // Save to memory cache
        memoryCache.put(fileName, bitmap)
    }

    // Call this method to cancel the scope when you no longer need it (after image load or error)
    private fun cancelScope() {
        if (scope.isActive) {
            scope.cancel()  // Cancels all coroutines running under this scope
        }
    }

    // Static Builder class to customize ImageLoader
    class Builder(private val context: Context) {
        private var placeholder: Int? = null
        private var errorImage: Int? = null

        // Set placeholder image
        fun placeholder(placeholder: Int): Builder {
            this.placeholder = placeholder
            return this
        }

        // Set error image
        fun errorImage(errorImage: Int): Builder {
            this.errorImage = errorImage
            return this
        }

        // Build the ImageLoader instance
        fun build(): InstaImageLoader {
            return InstaImageLoader(context, placeholder, errorImage)
        }
    }

    // Companion object to provide a static entry point for the Builder pattern
    companion object {
        // Builder method to start building the ImageLoader instance
        fun with(context: Context): InstaImageLoader {
            return Builder(context).build()
        }
    }
}



