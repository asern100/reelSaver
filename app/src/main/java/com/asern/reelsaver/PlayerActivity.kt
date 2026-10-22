package com.asern.reelsaver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Built-in player for video and audio */
class PlayerActivity : AppCompatActivity() {

    private lateinit var video: VideoView
    private var position = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val uri = Uri.parse(intent.getStringExtra("uri") ?: run { finish(); return })
        val mime = intent.getStringExtra("mime").orEmpty()
        val audio = mime.startsWith("audio")

        findViewById<TextView>(R.id.playerTitle).text = intent.getStringExtra("title")
        findViewById<View>(R.id.audioArt).visibility = if (audio) View.VISIBLE else View.GONE

        video = findViewById(R.id.video)
        val controller = MediaController(this)
        controller.setAnchorView(findViewById(R.id.playerRoot))
        video.setMediaController(controller)
        video.setVideoURI(uri)
        video.setOnPreparedListener {
            video.seekTo(position)
            video.start()
            controller.show(0)
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.player_failed, Toast.LENGTH_SHORT).show()
            openExternal(uri, mime)
            true
        }

        findViewById<Button>(R.id.externalBtn).setOnClickListener { openExternal(uri, mime) }
        findViewById<Button>(R.id.shareBtn).apply {
            visibility = if (uri.scheme == "content") View.VISIBLE else View.GONE
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.share)))
            }
        }
        findViewById<Button>(R.id.externalBtn).visibility =
            if (uri.scheme == "content") View.VISIBLE else View.GONE
    }

    private fun openExternal(uri: Uri, mime: String) {
        if (uri.scheme != "content") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        position = video.currentPosition
        video.pause()
    }
}
