package com.nightlynexus.exifdataremover

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.Intent.ACTION_PICK
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.UUID

class Activity : AppCompatActivity() {
  private lateinit var scope: CoroutineScope

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    scope = MainScope()
    setContentView(R.layout.activity)
    val toolbar = findViewById<Toolbar>(R.id.toolbar)
    val button = findViewById<View>(R.id.button)
    // There appears to be no way to delete the directory on Android 29+.
    if (SDK_INT < 29) {
      toolbar.inflateMenu(R.menu.toolbar)
      toolbar.setOnMenuItemClickListener {
        when (it.itemId) {
          R.id.menu_delete_all -> {
            if (checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
              deleteAll()
            } else {
              requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), 2)
            }
            true
          }
          else -> throw AssertionError("itemId == ${it.itemId}")
        }
      }
    }
    button.setOnClickListener {
      if (SDK_INT >= 29 || checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
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
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
    val intent = Intent(ACTION_PICK)
    intent.setDataAndType(EXTERNAL_CONTENT_URI, "image/jpeg")
    startActivityForResult(intent, 1)
  }

  private fun deleteAll() {
    scope.launch(Dispatchers.IO) {
      val parent = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Exif Data Remover"
      )
      parent.deleteRecursively()
    }
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode != RESULT_OK) {
      return
    }
    val sourceUri = data!!.data!!
    scope.launch(Dispatchers.IO) {
      val fileName = "${UUID.randomUUID()}.jpg"
      if (SDK_INT >= 29) {
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
          put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_PICTURES}${File.separator}Exif Data Remover"
          )
          put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        try {
          @SuppressLint("Recycle") // Handled in copyWithoutExifData.
          val source = contentResolver.openInputStream(sourceUri)!!.source().buffer()
          val sinkUri = contentResolver.insert(EXTERNAL_CONTENT_URI, values)!!
          @SuppressLint("Recycle") // Handled in copyWithoutExifData.
          val sink = contentResolver.openOutputStream(sinkUri)!!.sink().buffer()
          if (copyWithoutExifData(source, sink)) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(sinkUri, values, null, null)
            withContext(Dispatchers.Main) {
              startActivity(
                Intent(
                  ACTION_VIEW,
                  sinkUri
                ).addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
              )
            }
          } else {
            withContext(Dispatchers.Main) {
              Toast.makeText(this@Activity, R.string.only_jpeg, LENGTH_LONG)
                .show()
            }
          }
        } catch (e: IOException) {
          withContext(Dispatchers.Main) {
            Toast.makeText(
              this@Activity, getString(R.string.failed_to_create, e.message), LENGTH_LONG
            )
              .show()
          }
        }
      } else {
        val parent = File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
          "Exif Data Remover"
        )
        parent.mkdirs()
        val output = File(parent, fileName)
        try {
          @SuppressLint("Recycle") // Handled in copyWithoutExifData.
          val source = contentResolver.openInputStream(sourceUri)!!.source().buffer()
          if (copyWithoutExifData(source, output.sink().buffer())) {
            MediaScannerConnection.scanFile(
              this@Activity, arrayOf(output.path), arrayOf("image/jpeg"), null
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
          } else {
            output.delete()

            withContext(Dispatchers.Main) {
              Toast.makeText(this@Activity, R.string.only_jpeg, LENGTH_LONG)
                .show()
            }
          }
        } catch (e: IOException) {
          output.delete()

          withContext(Dispatchers.Main) {
            Toast.makeText(
              this@Activity, getString(R.string.failed_to_create, e.message), LENGTH_LONG
            )
              .show()
          }
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }
}
