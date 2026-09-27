package com.world2d.engine.editor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native design system with 48dp touch targets and no WebView. */
public final class Ui {
    public static final int BG=Color.rgb(14,19,29),SURFACE=Color.rgb(23,31,44),RAISED=Color.rgb(30,42,57),
        BORDER=Color.rgb(49,64,80),TEXT=Color.rgb(232,243,239),MUTED=Color.rgb(155,176,187),
        GREEN=Color.rgb(185,235,137),DARK_GREEN=Color.rgb(35,55,41),AMBER=Color.rgb(255,193,119),
        RED=Color.rgb(238,130,137);
    private Ui() {}
    public static int dp(Context c,float size){return Math.round(size*c.getResources().getDisplayMetrics().density);}
    public static GradientDrawable background(Context c,int color,int radius,int stroke){
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(c,radius));
        if(stroke!=0)shape.setStroke(dp(c,1),stroke);return shape;
    }
    public static TextView text(Context c,String label,int sp,int color,boolean bold){
        TextView view=new TextView(c);view.setText(label);view.setTextSize(sp);view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setFontFeatureSettings("kern");
        if(bold)view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return view;
    }
    public static TextView button(Context c,String label,boolean primary){
        TextView button=text(c,label,13,primary?BG:TEXT,true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(c,46));button.setMinWidth(dp(c,48));
        button.setPadding(dp(c,14),dp(c,7),dp(c,14),dp(c,7));
        button.setBackground(background(c,primary?GREEN:RAISED,11,primary?0:BORDER));
        button.setClickable(true);button.setFocusable(true);
        button.setElevation(dp(c,primary?1:0));
        return button;
    }
    public static TextView subtleButton(Context c,String label){
        TextView button=text(c,label,13,MUTED,true);button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(c,44));button.setMinHeight(dp(c,46));
        button.setPadding(dp(c,9),0,dp(c,9),0);
        button.setBackground(background(c,SURFACE,9,0));button.setClickable(true);return button;
    }
    public static LinearLayout vertical(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    public static LinearLayout horizontal(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    public static LinearLayout card(Context c){LinearLayout v=vertical(c);v.setPadding(dp(c,15),dp(c,15),dp(c,15),dp(c,15));
        v.setBackground(background(c,SURFACE,16,BORDER));return v;}
    public static EditText field(Context c,String hint,String value,boolean numeric){
        EditText input=new EditText(c);input.setSingleLine(true);input.setText(value);input.setHint(hint);
        input.setTextSize(14);input.setTextColor(TEXT);input.setHintTextColor(MUTED);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10));
        input.setBackground(background(c,RAISED,9,BORDER));
        input.setInputType(numeric?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED:
            InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }
    public static void add(LinearLayout parent,View child,int width,int height){
        parent.addView(child,new LinearLayout.LayoutParams(width<0?width:dp(parent.getContext(),width),
            height<0?height:dp(parent.getContext(),height)));
    }
    public static void weighted(LinearLayout parent,View child){
        parent.addView(child,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
    }
    public static View space(Context c,int height){View v=new View(c);v.setLayoutParams(new ViewGroup.LayoutParams(1,dp(c,height)));return v;}
    public static View divider(Context c){View v=new View(c);v.setBackgroundColor(BORDER);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(c,1)));return v;}
    public static void pad(View v,Context c,int left,int top,int right,int bottom){v.setPadding(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));}
    public static int color(String value,int fallback){try{return Color.parseColor(value);}catch(Exception ex){return fallback;}}
    public static String titlecase(String input){return input.isEmpty()?input:input.substring(0,1).toUpperCase()+input.substring(1);}
}
