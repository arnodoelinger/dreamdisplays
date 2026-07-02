use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicI64, Ordering};

use ffmpeg::ffi;
use ffmpeg::format::Pixel;
use ffmpeg::util::frame::video::Video as VideoFrame;
use ffmpeg_next as ffmpeg;

use crate::session::{ERR_BAD_ARGS, ERR_BAD_HANDLE, ERR_IO};

pub const ERR_UNSUPPORTED: i32 = -4;
pub const SURFACE_ABI_VERSION: u32 = 1;
pub const SURFACE_PLATFORM_MACOS_IOSURFACE: u32 = 1;
pub const SURFACE_FORMAT_NV12_8: u32 = 1;
pub const SURFACE_FORMAT_P010_10: u32 = 2;

pub const GL_TEXTURE_RECTANGLE: u32 = 0x84F5;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct LavSurfaceDesc {
    pub handle: i64,
    pub platform: u32,
    pub format: u32,
    pub width: u32,
    pub height: u32,
    pub plane_count: u32,
    pub texture_target: u32,
    pub plane_width: [u32; 4],
    pub plane_height: [u32; 4],
    pub reserved: [u32; 4],
}

impl Default for LavSurfaceDesc {
    fn default() -> Self {
        LavSurfaceDesc {
            handle: 0,
            platform: 0,
            format: 0,
            width: 0,
            height: 0,
            plane_count: 0,
            texture_target: 0,
            plane_width: [0; 4],
            plane_height: [0; 4],
            reserved: [0; 4],
        }
    }
}

pub struct LavSurfaceTable {
    map: Mutex<HashMap<i64, LavSurfaceFrame>>,
    next: AtomicI64,
}

impl LavSurfaceTable {
    pub fn new() -> LavSurfaceTable {
        LavSurfaceTable {
            map: Mutex::new(HashMap::new()),
            next: AtomicI64::new(1),
        }
    }

    pub fn insert(&self, mut surface: LavSurfaceFrame, desc: &mut LavSurfaceDesc) -> i32 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        surface.desc.handle = handle;
        *desc = surface.desc;
        match self.map.lock() {
            Ok(mut map) => {
                map.insert(handle, surface);
                0
            }
            Err(_) => ERR_IO,
        }
    }

    pub fn release(&self, handle: i64) {
        if let Ok(mut map) = self.map.lock() {
            map.remove(&handle);
        }
    }

    pub fn bind_plane_gl(&self, handle: i64, plane: u32, texture_id: u32) -> i32 {
        if texture_id == 0 {
            return ERR_BAD_ARGS;
        }
        let Ok(map) = self.map.lock() else {
            return ERR_IO;
        };
        let Some(surface) = map.get(&handle) else {
            return ERR_BAD_HANDLE;
        };
        surface.bind_plane_gl(plane, texture_id)
    }
}

pub struct LavSurfaceFrame {
    frame: *mut ffi::AVFrame,
    desc: LavSurfaceDesc,
}

// The AVFrame owns refcounted hardware buffers. Access to the handle table is serialized and
// platform import calls only read the retained frame reference.
unsafe impl Send for LavSurfaceFrame {}

impl Drop for LavSurfaceFrame {
    fn drop(&mut self) {
        unsafe {
            if !self.frame.is_null() {
                ffi::av_frame_free(&mut self.frame);
            }
        }
    }
}

impl LavSurfaceFrame {
    pub fn from_video_frame(frame: &VideoFrame) -> Result<LavSurfaceFrame, String> {
        if frame.format() != Pixel::VIDEOTOOLBOX {
            return Err(format!(
                "Zero-copy surface import is unsupported for {:?}.",
                frame.format()
            ));
        }
        unsafe {
            let cloned = ffi::av_frame_clone(frame.as_ptr());
            if cloned.is_null() {
                return Err("av_frame_clone failed.".to_string());
            }
            let desc = match describe_frame(cloned, frame.format()) {
                Ok(desc) => desc,
                Err(e) => {
                    let mut owned = cloned;
                    ffi::av_frame_free(&mut owned);
                    return Err(e);
                }
            };
            Ok(LavSurfaceFrame {
                frame: cloned,
                desc,
            })
        }
    }

    fn bind_plane_gl(&self, plane: u32, texture_id: u32) -> i32 {
        #[cfg(target_os = "macos")]
        {
            if self.desc.platform == SURFACE_PLATFORM_MACOS_IOSURFACE {
                return unsafe {
                    macos::bind_iosurface_plane_gl(self.frame, &self.desc, plane, texture_id)
                };
            }
        }
        let _ = plane;
        let _ = texture_id;
        ERR_UNSUPPORTED
    }
}

unsafe fn describe_frame(
    frame: *mut ffi::AVFrame,
    format: Pixel,
) -> Result<LavSurfaceDesc, String> {
    match format {
        Pixel::VIDEOTOOLBOX => {
            #[cfg(target_os = "macos")]
            {
                unsafe {
                    // SAFETY: `frame` is an AVFrame cloned from ffmpeg-next and its format was
                    // verified as VideoToolbox before this descriptor path is reached.
                    macos::describe_videotoolbox(frame)
                }
            }
            #[cfg(not(target_os = "macos"))]
            {
                let _ = frame;
                Err("VideoToolbox surface import is only available on macOS.".to_string())
            }
        }
        _ => Err(format!(
            "Zero-copy surface import is unsupported for {:?}.",
            format
        )),
    }
}

#[cfg(target_os = "macos")]
mod macos {
    use std::ffi::c_void;

    use ffmpeg::ffi;
    use ffmpeg_next as ffmpeg;

    use super::{
        GL_TEXTURE_RECTANGLE, LavSurfaceDesc, SURFACE_FORMAT_NV12_8, SURFACE_FORMAT_P010_10,
        SURFACE_PLATFORM_MACOS_IOSURFACE,
    };
    use crate::surface::{ERR_BAD_ARGS, ERR_IO, ERR_UNSUPPORTED};

    type CVPixelBufferRef = *const c_void;
    type IOSurfaceRef = *const c_void;
    type CGLContextObj = *mut c_void;
    type CGLError = i32;
    type GLenum = u32;
    type GLuint = u32;
    type GLsizei = i32;
    type OSType = u32;

    const GL_RED: GLenum = 0x1903;
    const GL_RG: GLenum = 0x8227;
    const GL_R8: GLenum = 0x8229;
    const GL_RG8: GLenum = 0x822B;
    const GL_R16: GLenum = 0x822A;
    const GL_RG16: GLenum = 0x822C;
    const GL_UNSIGNED_BYTE: GLenum = 0x1401;
    const GL_UNSIGNED_SHORT: GLenum = 0x1403;

    const CV_420V: OSType = u32::from_be_bytes(*b"420v");
    const CV_420F: OSType = u32::from_be_bytes(*b"420f");
    const CV_X420: OSType = u32::from_be_bytes(*b"x420");
    const CV_XF20: OSType = u32::from_be_bytes(*b"xf20");

    #[link(name = "CoreVideo", kind = "framework")]
    unsafe extern "C" {
        fn CVPixelBufferGetIOSurface(pixel_buffer: CVPixelBufferRef) -> IOSurfaceRef;
        fn CVPixelBufferGetPixelFormatType(pixel_buffer: CVPixelBufferRef) -> OSType;
    }

    #[link(name = "IOSurface", kind = "framework")]
    unsafe extern "C" {
        fn IOSurfaceGetPlaneCount(buffer: IOSurfaceRef) -> usize;
        fn IOSurfaceGetWidthOfPlane(buffer: IOSurfaceRef, plane: usize) -> usize;
        fn IOSurfaceGetHeightOfPlane(buffer: IOSurfaceRef, plane: usize) -> usize;
    }

    #[link(name = "OpenGL", kind = "framework")]
    unsafe extern "C" {
        fn CGLGetCurrentContext() -> CGLContextObj;
        fn CGLTexImageIOSurface2D(
            ctx: CGLContextObj,
            target: GLenum,
            internal_format: GLenum,
            width: GLsizei,
            height: GLsizei,
            format: GLenum,
            ty: GLenum,
            surface: IOSurfaceRef,
            plane: GLuint,
        ) -> CGLError;
        fn glBindTexture(target: GLenum, texture: GLuint);
        fn glTexParameteri(target: GLenum, pname: GLenum, param: i32);
    }

    const GL_TEXTURE_MIN_FILTER: GLenum = 0x2801;
    const GL_TEXTURE_MAG_FILTER: GLenum = 0x2800;
    const GL_TEXTURE_WRAP_S: GLenum = 0x2802;
    const GL_TEXTURE_WRAP_T: GLenum = 0x2803;
    const GL_LINEAR: i32 = 0x2601;
    const GL_CLAMP_TO_EDGE: i32 = 0x812F;

    pub unsafe fn describe_videotoolbox(
        frame: *mut ffi::AVFrame,
    ) -> Result<LavSurfaceDesc, String> {
        let pixel_buffer = unsafe {
            // Safety: frame is a retained VideoToolbox AVFrame supplied by the caller
            pixel_buffer(frame)?
        };
        let surface = unsafe {
            // Safety: pixel_buffer is a valid CVPixelBufferRef extracted from the frame
            CVPixelBufferGetIOSurface(pixel_buffer)
        };
        if surface.is_null() {
            return Err("VideoToolbox frame has no IOSurface backing.".to_string());
        }

        let pixel_format = unsafe {
            // Safety: pixel_buffer is a valid CVPixelBufferRef extracted from the frame
            CVPixelBufferGetPixelFormatType(pixel_buffer)
        };
        let format = match pixel_format {
            CV_420V | CV_420F => SURFACE_FORMAT_NV12_8,
            CV_X420 | CV_XF20 => SURFACE_FORMAT_P010_10,
            other => {
                return Err(format!(
                    "Unsupported CVPixelBuffer pixel format 0x{other:08x}."
                ));
            }
        };

        let plane_count = unsafe {
            // Safety: surface is a non-null IOSurfaceRef from CoreVideo
            IOSurfaceGetPlaneCount(surface)
        };
        if plane_count < 2 {
            return Err(format!(
                "Expected at least two IOSurface planes, got {plane_count}."
            ));
        }

        let mut desc = LavSurfaceDesc::default();
        desc.platform = SURFACE_PLATFORM_MACOS_IOSURFACE;
        desc.format = format;
        desc.texture_target = GL_TEXTURE_RECTANGLE;
        desc.plane_count = 2;
        for plane in 0..2 {
            desc.plane_width[plane] = unsafe {
                // Safety: surface has at least two planes, verified above
                IOSurfaceGetWidthOfPlane(surface, plane) as u32
            };
            desc.plane_height[plane] = unsafe {
                // Safety: surface has at least two planes, verified above
                IOSurfaceGetHeightOfPlane(surface, plane) as u32
            };
        }
        desc.width = desc.plane_width[0];
        desc.height = desc.plane_height[0];
        Ok(desc)
    }

    pub unsafe fn bind_iosurface_plane_gl(
        frame: *mut ffi::AVFrame,
        desc: &LavSurfaceDesc,
        plane: u32,
        texture_id: u32,
    ) -> i32 {
        if plane >= desc.plane_count || texture_id == 0 {
            return ERR_BAD_ARGS;
        }
        let pixel_buffer = match unsafe {
            // Safety: frame is a retained VideoToolbox AVFrame supplied by the caller
            pixel_buffer(frame)
        } {
            Ok(p) => p,
            Err(_) => return ERR_BAD_ARGS,
        };
        let surface = unsafe {
            // Safety: pixel_buffer is a valid CVPixelBufferRef extracted from the frame
            CVPixelBufferGetIOSurface(pixel_buffer)
        };
        if surface.is_null() {
            return ERR_UNSUPPORTED;
        }
        let ctx = unsafe {
            // Safety: queries thread-local OpenGL state; returns null when no context is current
            CGLGetCurrentContext()
        };
        if ctx.is_null() {
            return ERR_IO;
        }

        let (internal_format, format, ty) = match (desc.format, plane) {
            (SURFACE_FORMAT_NV12_8, 0) => (GL_R8, GL_RED, GL_UNSIGNED_BYTE),
            (SURFACE_FORMAT_NV12_8, 1) => (GL_RG8, GL_RG, GL_UNSIGNED_BYTE),
            (SURFACE_FORMAT_P010_10, 0) => (GL_R16, GL_RED, GL_UNSIGNED_SHORT),
            (SURFACE_FORMAT_P010_10, 1) => (GL_RG16, GL_RG, GL_UNSIGNED_SHORT),
            _ => return ERR_UNSUPPORTED,
        };

        let rc = unsafe {
            // Safety: a current CGL context was verified above; texture_id is non-zero; surface
            // is non-null; plane is in range for desc.
            glBindTexture(GL_TEXTURE_RECTANGLE, texture_id);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            CGLTexImageIOSurface2D(
                ctx,
                GL_TEXTURE_RECTANGLE,
                internal_format,
                desc.plane_width[plane as usize] as GLsizei,
                desc.plane_height[plane as usize] as GLsizei,
                format,
                ty,
                surface,
                plane,
            )
        };
        if rc == 0 { 0 } else { ERR_IO }
    }

    unsafe fn pixel_buffer(frame: *mut ffi::AVFrame) -> Result<CVPixelBufferRef, String> {
        if frame.is_null() {
            return Err("Null AVFrame".to_string());
        }
        let pixel_buffer = unsafe {
            // Safety: frame is non-null and points to a VideoToolbox AVFrame; data[3] is where
            // libav exposes the CVPixelBufferRef for this hardware frame type.
            (*frame).data[3] as CVPixelBufferRef
        };
        if pixel_buffer.is_null() {
            Err("VideoToolbox frame has no CVPixelBufferRef in data[3].".to_string())
        } else {
            Ok(pixel_buffer)
        }
    }
}
