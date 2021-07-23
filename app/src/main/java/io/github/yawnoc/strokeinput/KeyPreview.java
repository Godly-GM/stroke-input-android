/*
  Copyright 2021 Conway
  Licensed under the GNU General Public License v3.0 (GPL-3.0-only).
  This is free software with NO WARRANTY etc. etc.,
  see LICENSE or <https://www.gnu.org/licenses/>.
*/

package io.github.yawnoc.strokeinput;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;

/*
  A key preview, to be displayed in a PopupWindow.
*/
public class KeyPreview extends View {
  
  // Properties
  private Key activeKey;
  private int width;
  private int height;
  private String displayText;
  
  // Key preview drawing
  private final Rect rectangle;
  private final Paint fillPaint;
  private final Paint borderPaint;
  private final Paint textPaint;
  
  public KeyPreview(final Context context) {
    
    super(context);
    
    rectangle = new Rect();
    
    fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    fillPaint.setStyle(Paint.Style.FILL);
    
    borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    borderPaint.setStyle(Paint.Style.STROKE);
    
    textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    textPaint.setTypeface(
      Typeface.createFromAsset(
        context.getAssets(),
        InputContainer.KEYBOARD_FONT
      )
    );
    textPaint.setTextAlign(Paint.Align.CENTER);
  }
  
  public void update(final Key key, final int shiftMode) {
    
    activeKey = key;
    if (activeKey == null) {
      return;
    }
    
    width = key.width;
    height = key.height;
    displayText = key.shiftAwareDisplayText(shiftMode);
    
    rectangle.set(0, 0, key.width, key.height);
    
    fillPaint.setColor(key.fillColour);
    borderPaint.setColor(key.borderColour);
    borderPaint.setStrokeWidth(key.borderThickness);
    
    textPaint.setColor(key.textColour);
    textPaint.setTextSize(key.textSize);
  }
  
  @Override
  public void onMeasure(
    final int widthMeasureSpec,
    final int heightMeasureSpec
  )
  {
    setMeasuredDimension(width, height);
  }
  
  @Override
  public void onDraw(final Canvas canvas) {
    
    if (activeKey == null) {
      return;
    }
    
    canvas.drawRect(rectangle, fillPaint);
    canvas.drawRect(rectangle, borderPaint);
    canvas.drawText(
      displayText,
      width / 2f,
      height / 2f,
      textPaint
    );
  }
  
}