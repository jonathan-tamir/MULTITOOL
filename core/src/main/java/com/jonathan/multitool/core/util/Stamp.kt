package com.jonathan.multitool.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Filename timestamp shared by every exporter. */
fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
