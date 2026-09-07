package com.example.ialocal.audio

data class DecodedAudio(
    val pcm16: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
)
