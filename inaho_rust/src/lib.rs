/*
 * Inaho Music Player - Native Rust & ARM64 Assembly Core
 * Copyright (C) 2026 Kanagawa Yamada
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

pub mod art_extractor;

use std::fs::File;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;

/// Extract art directly from a Linux file descriptor without copying or buffering
#[no_mangle]
pub extern "system" fn Java_com_kanagawa_yamada_inaho_FastArtExtractor_nativeExtractArtFd(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jbyteArray {
    if fd < 0 {
        return std::ptr::null_mut();
    }

    #[cfg(unix)]
    {
        if let Some(bytes) = art_extractor::extract_art_from_raw_fd(fd) {
            match env.byte_array_from_slice(&bytes) {
                Ok(j_arr) => return j_arr.into_raw(),
                Err(_) => return std::ptr::null_mut(),
            }
        }
    }

    std::ptr::null_mut()
}

/// Extract art from an in-memory byte array
#[no_mangle]
pub extern "system" fn Java_com_kanagawa_yamada_inaho_FastArtExtractor_nativeExtractArtFromBytes(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jbyteArray {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    if let Some(art_bytes) = art_extractor::extract_art_from_slice(&bytes) {
        match env.byte_array_from_slice(&art_bytes) {
            Ok(j_arr) => j_arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}

/// Extract art from a direct file system path
#[no_mangle]
pub extern "system" fn Java_com_kanagawa_yamada_inaho_FastArtExtractor_nativeExtractArtFromPath(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jbyteArray {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut file = match File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return std::ptr::null_mut(),
    };

    if let Some(bytes) = art_extractor::extract_art_from_reader(&mut file) {
        match env.byte_array_from_slice(&bytes) {
            Ok(j_arr) => j_arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}
