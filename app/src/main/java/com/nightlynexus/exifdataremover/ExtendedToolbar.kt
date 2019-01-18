package com.nightlynexus.exifdataremover

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import androidx.appcompat.widget.Toolbar

private class ExtendedToolbar(
  context: Context,
  attrs: AttributeSet
) : Toolbar(context, attrs) {
  override fun onMeasure(
    widthMeasureSpec: Int,
    heightMeasureSpec: Int
  ) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec + paddingTop)
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    setPadding(0, insets.systemWindowInsetTop, 0, 0)
    return super.onApplyWindowInsets(insets)
  }
}
