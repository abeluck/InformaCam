LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_LDLIBS := -llog

LOCAL_MODULE    := JpegRedaction
LOCAL_SRC_FILES := iptc.cpp jpeg.cpp  tiff_ifd.cpp tiff_tag.cpp jpeg_decoder.cpp \
        		byte_swapping.cpp jpeg_marker.cpp debug_flag.cpp \
        		org_witness_informacam_app_editors_image_ImageConstructor.cpp


include $(BUILD_SHARED_LIBRARY)

