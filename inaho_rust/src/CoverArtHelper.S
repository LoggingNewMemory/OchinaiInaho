/*
 * Inaho Music Player - Native ARM64 Cover Art Assembly Helpers
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

#if defined(__aarch64__)

.text
.align 2

/*
 * u32 synchsafe_arm64(u32 raw_be_value);
 *
 * Decodes a 28-bit synchsafe integer from a 4-byte big-endian integer.
 * In ID3v2, each byte has its MSB set to 0 (7 data bits per byte):
 * (b0 << 21) | (b1 << 14) | (b2 << 7) | b3
 *
 * Input:
 *   w0 = big-endian 32-bit integer containing [b0, b1, b2, b3]
 * Output:
 *   w0 = 28-bit decoded integer
 */
.global synchsafe_arm64
.type synchsafe_arm64, %function
synchsafe_arm64:
    ubfx    w1, w0, #24, #7       // w1 = byte 0 (bits 24..30)
    ubfx    w2, w0, #16, #7       // w2 = byte 1 (bits 16..22)
    ubfx    w3, w0, #8,  #7       // w3 = byte 2 (bits 8..14)
    and     w0, w0, #0x7f         // w0 = byte 3 (bits 0..6)
    orr     w0, w0, w3, lsl #7    // pack byte 2
    orr     w0, w0, w2, lsl #14   // pack byte 1
    orr     w0, w0, w1, lsl #21   // pack byte 0
    ret

/*
 * size_t find_null_terminator_arm64(const uint8_t *ptr, size_t max_len);
 *
 * Scans a byte buffer for the first 0x00 byte (null terminator in ISO-8859-1 or UTF-8).
 *
 * Input:
 *   x0 = pointer to bytes
 *   x1 = max_len
 * Output:
 *   x0 = offset of first 0x00, or max_len if not found
 */
.global find_null_terminator_arm64
.type find_null_terminator_arm64, %function
find_null_terminator_arm64:
    mov     x2, #0
1:
    cmp     x2, x1
    b.ge    2f
    ldrb    w3, [x0, x2]
    cbz     w3, 2f
    add     x2, x2, #1
    b       1b
2:
    mov     x0, x2
    ret

/*
 * size_t find_utf16_null_terminator_arm64(const uint8_t *ptr, size_t max_len);
 *
 * Scans a byte buffer for the first 0x0000 16-bit halfword (null terminator in UTF-16).
 *
 * Input:
 *   x0 = pointer to bytes
 *   x1 = max_len
 * Output:
 *   x0 = offset of first 0x0000, or max_len if not found
 */
.global find_utf16_null_terminator_arm64
.type find_utf16_null_terminator_arm64, %function
find_utf16_null_terminator_arm64:
    cmp     x1, #2
    b.lt    3f
    sub     x2, x1, #1
    mov     x3, #0
1:
    cmp     x3, x2
    b.ge    3f
    ldrh    w4, [x0, x3]
    cbz     w4, 2f
    add     x3, x3, #1
    b       1b
2:
    mov     x0, x3
    ret
3:
    mov     x0, x1
    ret

#endif
