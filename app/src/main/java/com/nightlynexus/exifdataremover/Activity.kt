package com.nightlynexus.exifdataremover

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Intent
import android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE
import android.content.Intent.ACTION_PICK
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.view.View
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeHex
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.UUID

class Activity : AppCompatActivity(), CoroutineScope {
  private lateinit var job: Job
  private lateinit var parent: File

  override val coroutineContext
    get() = Dispatchers.IO + job

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    job = Job()
    parent = File(Environment.getExternalStorageDirectory(), "Exif Data Remover")
    setContentView(R.layout.activity_main)
    val toolbar = findViewById<Toolbar>(R.id.toolbar)
    val button = findViewById<View>(R.id.button)
    toolbar.inflateMenu(R.menu.toolbar)
    toolbar.setOnMenuItemClickListener {
      if (checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED
      ) {
        deleteAll()
      } else {
        requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), 2)
      }
      true
    }
    button.setOnClickListener {
      if (checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED
      ) {
        startImagePickerForResult()
      } else {
        requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), 1)
      }
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    if (grantResults[0] == PERMISSION_GRANTED) {
      when (requestCode) {
        1 -> {
          startImagePickerForResult()
        }
        2 -> {
          deleteAll()
        }
        else -> throw AssertionError("requestCode == $requestCode")
      }
    } else {
      startActivity(
          Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
      )
    }
  }

  private fun startImagePickerForResult() {
    startActivityForResult(Intent(ACTION_PICK, EXTERNAL_CONTENT_URI), 1)
  }

  private fun deleteAll() {
    launch {
      parent.deleteRecursively()
    }
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    if (resultCode != RESULT_OK) {
      return
    }
    val uri = data!!.data!!
    launch {
      parent.mkdirs()
      // Assume we have permission.
      // If the permission was revoked before getting to onActivityResult,
      // assume that Android will restart our process instead.
      val output = File(parent, "${UUID.randomUUID()}.jpg")
      var success = false
      try {
        output.sink()
            .buffer()
            .use { sink ->
              contentResolver.openInputStream(uri)!!.source()
                  .buffer()
                  .use { source ->
                    if (source.rangeEquals(0, jpegFileStart)) {
                      sink.write(source, 2)
                      val sourceBuffer = source.buffer
                      while (true) {
                        source.require(2)
                        if (sourceBuffer[0] != marker) {
                          throw IOException("${sourceBuffer[0]} != $marker")
                        }
                        val nextByte = sourceBuffer[1]
                        if (nextByte == APP1 || nextByte == comment) {
                          source.skip(2)
                          val size = source.readShort()
                          source.skip((size - 2).toLong())
                        } else if (nextByte == startOfStream) {
                          sink.writeAll(source)
                          break
                        } else {
                          sink.write(source, 2)
                          val size = source.readShort()
                          sink.writeShort(size.toInt())
                          sink.write(source, (size - 2).toLong())
                        }
                      }
                      success = true
                    } else {
                      withContext(Dispatchers.Main) {
                        Toast.makeText(this@Activity, R.string.only_jpeg, LENGTH_LONG)
                            .show()
                      }
                    }
                  }
            }
      } catch (e: IOException) {
        withContext(Dispatchers.Main) {
          Toast.makeText(
              this@Activity, getString(R.string.failed_to_create, e.message), LENGTH_LONG
          )
              .show()
        }
      } finally {
        if (success) {
          withContext(Dispatchers.Main) {
            sendBroadcast(
                Intent(
                    ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(output)
                )
            )
            startActivity(
                Intent(
                    ACTION_VIEW,
                    FileProvider.getUriForFile(
                        this@Activity,
                        "$packageName.fileprovider",
                        output
                    )
                ).addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
            )
          }
        } else {
          output.delete()
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    job.cancel()
  }
}

private val jpegFileStart = "FFD8".decodeHex()
private const val marker = 0xFF.toByte()
private const val APP1 = 0xE1.toByte()
private const val comment = 0xFE.toByte()
private const val startOfStream = 0xDA.toByte()
