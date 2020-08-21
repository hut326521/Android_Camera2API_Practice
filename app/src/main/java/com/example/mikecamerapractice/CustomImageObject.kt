package com.example.mikecamerapractice

class CustomImageObject(yArray: ByteArray,uArray: ByteArray,vArray: ByteArray,width: Int,height: Int,yRowstride: Int,uvRowstride: Int,uvPixelStride: Int) {

    val mYArray: ByteArray = yArray
    val mUArray: ByteArray = uArray
    val mVArray: ByteArray = vArray
    val mWidth: Int = width
    val mHeight: Int = height
    val mYRowstride: Int = yRowstride
    val mUVRowstride: Int = uvRowstride
    val mUVPixelStride: Int = uvPixelStride

}