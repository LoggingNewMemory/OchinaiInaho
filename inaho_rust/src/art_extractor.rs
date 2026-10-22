/*
 * Inaho Music Player - Native Fast Cover Art & Metadata Extractor
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

use std::fs::File;
use std::io::{Cursor, Read, Seek, SeekFrom};
#[cfg(unix)]
use std::os::unix::io::FromRawFd;

// ==========================================
// ARM64 ASSEMBLY EXTERNAL DECLARATIONS
// ==========================================

#[cfg(target_arch = "aarch64")]
extern "C" {
    fn synchsafe_arm64(raw_be: u32) -> u32;
    fn find_null_terminator_arm64(ptr: *const u8, max_len: usize) -> usize;
    fn find_utf16_null_terminator_arm64(ptr: *const u8, max_len: usize) -> usize;
}

#[inline(always)]
pub fn synchsafe(bytes: [u8; 4]) -> u32 {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        synchsafe_arm64(u32::from_be_bytes(bytes))
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        ((bytes[0] as u32 & 0x7F) << 21)
            | ((bytes[1] as u32 & 0x7F) << 14)
            | ((bytes[2] as u32 & 0x7F) << 7)
            | (bytes[3] as u32 & 0x7F)
    }
}

#[inline(always)]
pub fn find_null(slice: &[u8]) -> Option<usize> {
    if slice.is_empty() {
        return None;
    }
    #[cfg(target_arch = "aarch64")]
    {
        let offset = unsafe { find_null_terminator_arm64(slice.as_ptr(), slice.len()) };
        if offset < slice.len() {
            Some(offset)
        } else {
            None
        }
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        slice.iter().position(|&b| b == 0)
    }
}

#[inline(always)]
pub fn find_utf16_null(slice: &[u8]) -> Option<usize> {
    if slice.len() < 2 {
        return None;
    }
    #[cfg(target_arch = "aarch64")]
    {
        let offset = unsafe { find_utf16_null_terminator_arm64(slice.as_ptr(), slice.len()) };
        if offset < slice.len() - 1 {
            Some(offset)
        } else {
            None
        }
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        slice.windows(2).position(|w| w[0] == 0 && w[1] == 0)
    }
}

// ==========================================
// CORE COVER ART EXTRACTOR
// ==========================================

/// Extract cover art from a direct Linux/Android file descriptor
#[cfg(unix)]
pub fn extract_art_from_raw_fd(fd: std::os::unix::io::RawFd) -> Option<Vec<u8>> {
    // Duplicate file descriptor so File drop won't close caller's fd
    let dup_fd = unsafe { libc::dup(fd) };
    if dup_fd < 0 {
        return None;
    }
    let mut file = unsafe { File::from_raw_fd(dup_fd) };
    extract_art_from_reader(&mut file)
}

/// Extract cover art from a byte slice (in-memory buffer)
pub fn extract_art_from_slice(data: &[u8]) -> Option<Vec<u8>> {
    let mut cursor = Cursor::new(data);
    extract_art_from_reader(&mut cursor)
}

/// Generic seekable stream cover art extractor
pub fn extract_art_from_reader<R: Read + Seek>(reader: &mut R) -> Option<Vec<u8>> {
    reader.seek(SeekFrom::Start(0)).ok()?;

    let mut magic = [0u8; 4];
    if reader.read_exact(&mut magic).is_err() {
        return None;
    }

    if &magic[0..3] == b"ID3" {
        // ID3v2 (MP3, WAV with ID3, AIFF)
        reader.seek(SeekFrom::Start(0)).ok()?;
        extract_id3v2(reader)
    } else if &magic == b"fLaC" {
        // FLAC picture block
        extract_flac(reader)
    } else {
        // Check for MP4/M4A ('ftyp' atom at offset 4)
        let mut ftyp = [0u8; 4];
        if reader.read_exact(&mut ftyp).is_ok() && &ftyp == b"ftyp" {
            reader.seek(SeekFrom::Start(0)).ok()?;
            extract_mp4(reader)
        } else {
            None
        }
    }
}

// ==========================================
// ID3v2 PARSER (v2.2, v2.3, v2.4)
// ==========================================

fn extract_id3v2<R: Read + Seek>(reader: &mut R) -> Option<Vec<u8>> {
    let mut header = [0u8; 10];
    reader.read_exact(&mut header).ok()?;

    if &header[0..3] != b"ID3" {
        return None;
    }

    let version = header[3];
    let tag_size = synchsafe([header[6], header[7], header[8], header[9]]) as u64;

    let mut pos = 0u64;
    while pos < tag_size {
        if version >= 3 {
            // ID3v2.3 and ID3v2.4 (10 byte frame header)
            let mut frame_hdr = [0u8; 10];
            if reader.read_exact(&mut frame_hdr).is_err() {
                break;
            }

            // End of frames padding (all zeros)
            if frame_hdr[0] == 0 {
                break;
            }

            let id = &frame_hdr[0..4];
            let frame_size = if version == 4 {
                synchsafe([frame_hdr[4], frame_hdr[5], frame_hdr[6], frame_hdr[7]]) as u64
            } else {
                u32::from_be_bytes([frame_hdr[4], frame_hdr[5], frame_hdr[6], frame_hdr[7]]) as u64
            };

            if frame_size == 0 || pos + 10 + frame_size > tag_size + 10 {
                break;
            }

            if id == b"APIC" {
                return parse_apic_frame(reader, frame_size);
            } else {
                // Zero-copy fast seek to next frame!
                reader.seek(SeekFrom::Current(frame_size as i64)).ok()?;
            }
            pos += 10 + frame_size;
        } else {
            // ID3v2.2 (6 byte frame header: 3 byte ID, 3 byte size)
            let mut frame_hdr = [0u8; 6];
            if reader.read_exact(&mut frame_hdr).is_err() {
                break;
            }

            if frame_hdr[0] == 0 {
                break;
            }

            let id = &frame_hdr[0..3];
            let frame_size = ((frame_hdr[3] as u64) << 16)
                | ((frame_hdr[4] as u64) << 8)
                | (frame_hdr[5] as u64);

            if frame_size == 0 || pos + 6 + frame_size > tag_size + 10 {
                break;
            }

            if id == b"PIC" {
                return parse_pic_v22_frame(reader, frame_size);
            } else {
                reader.seek(SeekFrom::Current(frame_size as i64)).ok()?;
            }
            pos += 6 + frame_size;
        }
    }
    None
}

fn parse_apic_frame<R: Read + Seek>(reader: &mut R, frame_size: u64) -> Option<Vec<u8>> {
    // Read the APIC payload
    let mut data = vec![0u8; frame_size as usize];
    reader.read_exact(&mut data).ok()?;

    if data.is_empty() {
        return None;
    }

    let encoding = data[0];
    let mut offset = 1;

    // Scan MIME type (null terminated)
    let mime_end = find_null(&data[offset..])?;
    offset += mime_end + 1; // Skip MIME and null terminator

    if offset >= data.len() {
        return None;
    }

    // Skip picture type byte (1 byte)
    offset += 1;

    // Scan description string according to text encoding
    if encoding == 1 || encoding == 2 {
        // UTF-16 (double null terminator)
        let desc_end = find_utf16_null(&data[offset..])?;
        offset += desc_end + 2;
    } else {
        // ISO-8859-1 or UTF-8 (single null terminator)
        let desc_end = find_null(&data[offset..])?;
        offset += desc_end + 1;
    }

    if offset < data.len() {
        Some(data[offset..].to_vec())
    } else {
        None
    }
}

fn parse_pic_v22_frame<R: Read + Seek>(reader: &mut R, frame_size: u64) -> Option<Vec<u8>> {
    let mut data = vec![0u8; frame_size as usize];
    reader.read_exact(&mut data).ok()?;

    if data.len() < 5 {
        return None;
    }

    let encoding = data[0];
    // In ID3v2.2 PIC: 1 byte encoding, 3 bytes image format (e.g. "PNG" or "JPG"), 1 byte picture type
    let mut offset = 5;

    // Scan description
    if encoding == 1 || encoding == 2 {
        let desc_end = find_utf16_null(&data[offset..])?;
        offset += desc_end + 2;
    } else {
        let desc_end = find_null(&data[offset..])?;
        offset += desc_end + 1;
    }

    if offset < data.len() {
        Some(data[offset..].to_vec())
    } else {
        None
    }
}

// ==========================================
// FLAC PICTURE PARSER
// ==========================================

fn extract_flac<R: Read + Seek>(reader: &mut R) -> Option<Vec<u8>> {
    let mut is_last = false;

    while !is_last {
        let mut header = [0u8; 4];
        if reader.read_exact(&mut header).is_err() {
            break;
        }

        is_last = (header[0] & 0x80) != 0;
        let block_type = header[0] & 0x7F;
        let length = ((header[1] as u64) << 16) | ((header[2] as u64) << 8) | (header[3] as u64);

        if block_type == 6 {
            // PICTURE METADATA BLOCK
            // Read 4 bytes: picture type
            let mut u32_buf = [0u8; 4];
            reader.read_exact(&mut u32_buf).ok()?;

            // Read 4 bytes: MIME length
            reader.read_exact(&mut u32_buf).ok()?;
            let mime_len = u32::from_be_bytes(u32_buf) as i64;
            reader.seek(SeekFrom::Current(mime_len)).ok()?;

            // Read 4 bytes: description length
            reader.read_exact(&mut u32_buf).ok()?;
            let desc_len = u32::from_be_bytes(u32_buf) as i64;
            reader.seek(SeekFrom::Current(desc_len)).ok()?;

            // Skip 16 bytes: width(4), height(4), color depth(4), indexed colors(4)
            reader.seek(SeekFrom::Current(16)).ok()?;

            // Read 4 bytes: picture data length
            reader.read_exact(&mut u32_buf).ok()?;
            let pic_len = u32::from_be_bytes(u32_buf) as usize;

            let mut img_data = vec![0u8; pic_len];
            reader.read_exact(&mut img_data).ok()?;
            return Some(img_data);
        } else {
            // Skip non-picture metadata block in one fast zero-copy seek
            reader.seek(SeekFrom::Current(length as i64)).ok()?;
        }
    }
    None
}

// ==========================================
// MP4 / M4A COVER PARSER
// ==========================================

fn extract_mp4<R: Read + Seek>(reader: &mut R) -> Option<Vec<u8>> {
    let mut bytes_read = 0u64;
    let max_read = 10 * 1024 * 1024u64; // Max 10MB scan limit

    while bytes_read < max_read {
        let mut header = [0u8; 8];
        if reader.read_exact(&mut header).is_err() {
            break;
        }
        bytes_read += 8;

        let box_size = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as u64;
        let box_type = &header[4..8];

        if box_size < 8 {
            break;
        }

        if box_type == b"moov" || box_type == b"udta" || box_type == b"meta" || box_type == b"ilst" {
            // Container atom: dive inside
            if box_type == b"meta" {
                // meta box has a 4-byte version and flags header to skip
                reader.seek(SeekFrom::Current(4)).ok()?;
                bytes_read += 4;
            }
            continue;
        } else if box_type == b"covr" {
            // Inside covr box: find 'data' atom
            let mut data_header = [0u8; 8];
            if reader.read_exact(&mut data_header).is_err() {
                break;
            }

            let data_size = u32::from_be_bytes([data_header[0], data_header[1], data_header[2], data_header[3]]) as usize;
            let data_type = &data_header[4..8];

            if data_type == b"data" && data_size > 16 {
                // Skip 8 bytes (version/flags + 4 reserved bytes)
                reader.seek(SeekFrom::Current(8)).ok()?;
                let img_size = data_size - 16;
                let mut img_data = vec![0u8; img_size];
                if reader.read_exact(&mut img_data).is_ok() {
                    return Some(img_data);
                }
            }
            break;
        } else {
            // Skip other boxes
            let skip = box_size - 8;
            if bytes_read + skip > max_read {
                break;
            }
            reader.seek(SeekFrom::Current(skip as i64)).ok()?;
            bytes_read += skip;
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_synchsafe_u32() {
        assert_eq!(synchsafe([0, 0, 0, 127]), 127);
        assert_eq!(synchsafe([0, 0, 1, 0]), 128);
        assert_eq!(synchsafe([0, 0, 2, 1]), 257);
        assert_eq!(synchsafe([0x7f, 0x7f, 0x7f, 0x7f]), 0x0FFFFFFF);
    }

    #[test]
    fn test_null_finder() {
        assert_eq!(find_null(b"image/jpeg\0test"), Some(10));
        assert_eq!(find_null(b"no-zero"), None);
    }
}
