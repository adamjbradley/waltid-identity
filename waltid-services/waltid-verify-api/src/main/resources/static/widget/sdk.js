/**
 * Walt.id Verify Widget SDK
 * Version: 1.0.0
 *
 * A JavaScript SDK for embedding identity verification flows in web applications.
 *
 * Usage:
 *   WaltVerify.init({ clientToken: 'ct_xxx.yyy' });
 *   WaltVerify.verifyAge({ minAge: 18, onSuccess: (r) => console.log(r) });
 *
 * @license Apache-2.0
 * @copyright 2024 walt.id GmbH
 */

(function(global, factory) {
    'use strict';
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = factory();
    } else if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else {
        global.WaltVerify = factory();
    }
})(typeof window !== 'undefined' ? window : this, function() {
    'use strict';

    // ============================================================
    // QR Code Generator (Lightweight inline implementation)
    // Based on QR Code spec ISO/IEC 18004
    // ============================================================

    var QRCode = (function() {
        // Polynomial for error correction
        var QR_EC_POLYNOMIALS = {
            L: 1, M: 0, Q: 3, H: 2
        };

        // Mode indicators
        var MODE_BYTE = 4;

        // Error correction levels
        var EC_LEVEL = { L: 1, M: 0, Q: 3, H: 2 };

        // Galois field primitives
        var GF_EXP = new Array(512);
        var GF_LOG = new Array(256);

        (function initGaloisField() {
            var x = 1;
            for (var i = 0; i < 255; i++) {
                GF_EXP[i] = x;
                GF_LOG[x] = i;
                x <<= 1;
                if (x & 0x100) x ^= 0x11d;
            }
            for (var i = 255; i < 512; i++) {
                GF_EXP[i] = GF_EXP[i - 255];
            }
        })();

        function gfMul(a, b) {
            if (a === 0 || b === 0) return 0;
            return GF_EXP[GF_LOG[a] + GF_LOG[b]];
        }

        function polyMul(p1, p2) {
            var result = new Array(p1.length + p2.length - 1).fill(0);
            for (var i = 0; i < p1.length; i++) {
                for (var j = 0; j < p2.length; j++) {
                    result[i + j] ^= gfMul(p1[i], p2[j]);
                }
            }
            return result;
        }

        function generateECBytes(data, ecLen) {
            var generator = [1];
            for (var i = 0; i < ecLen; i++) {
                generator = polyMul(generator, [1, GF_EXP[i]]);
            }

            var poly = new Array(data.length + ecLen).fill(0);
            for (var i = 0; i < data.length; i++) poly[i] = data[i];

            for (var i = 0; i < data.length; i++) {
                var coef = poly[i];
                if (coef !== 0) {
                    for (var j = 0; j < generator.length; j++) {
                        poly[i + j] ^= gfMul(generator[j], coef);
                    }
                }
            }

            return poly.slice(data.length);
        }

        // Version capacity table (version, EC level) -> data capacity in bytes
        var VERSION_CAPACITY = {
            // Version 1-10 for L error correction (simplified)
            1: { L: 19, M: 16, Q: 13, H: 9 },
            2: { L: 34, M: 28, Q: 22, H: 16 },
            3: { L: 55, M: 44, Q: 34, H: 26 },
            4: { L: 80, M: 64, Q: 48, H: 36 },
            5: { L: 108, M: 86, Q: 62, H: 46 },
            6: { L: 136, M: 108, Q: 76, H: 60 },
            7: { L: 156, M: 124, Q: 88, H: 66 },
            8: { L: 194, M: 154, Q: 110, H: 86 },
            9: { L: 232, M: 182, Q: 132, H: 100 },
            10: { L: 274, M: 216, Q: 154, H: 122 },
            11: { L: 324, M: 254, Q: 180, H: 140 },
            12: { L: 370, M: 290, Q: 206, H: 158 },
            13: { L: 428, M: 334, Q: 244, H: 180 },
            14: { L: 461, M: 365, Q: 261, H: 197 },
            15: { L: 523, M: 415, Q: 295, H: 223 },
            16: { L: 589, M: 453, Q: 325, H: 253 },
            17: { L: 647, M: 507, Q: 367, H: 283 },
            18: { L: 721, M: 563, Q: 397, H: 313 },
            19: { L: 795, M: 627, Q: 445, H: 341 },
            20: { L: 861, M: 669, Q: 485, H: 385 },
            21: { L: 932, M: 714, Q: 512, H: 406 },
            22: { L: 1006, M: 782, Q: 568, H: 442 },
            23: { L: 1094, M: 860, Q: 614, H: 464 },
            24: { L: 1174, M: 914, Q: 664, H: 514 },
            25: { L: 1276, M: 1000, Q: 718, H: 538 },
            26: { L: 1370, M: 1062, Q: 754, H: 596 },
            27: { L: 1468, M: 1128, Q: 808, H: 628 },
            28: { L: 1531, M: 1193, Q: 871, H: 661 },
            29: { L: 1631, M: 1267, Q: 911, H: 701 },
            30: { L: 1735, M: 1373, Q: 985, H: 745 },
            31: { L: 1843, M: 1455, Q: 1033, H: 793 },
            32: { L: 1955, M: 1541, Q: 1115, H: 845 },
            33: { L: 2071, M: 1631, Q: 1171, H: 901 },
            34: { L: 2191, M: 1725, Q: 1231, H: 961 },
            35: { L: 2306, M: 1812, Q: 1286, H: 986 },
            36: { L: 2434, M: 1914, Q: 1354, H: 1054 },
            37: { L: 2566, M: 1992, Q: 1426, H: 1096 },
            38: { L: 2702, M: 2102, Q: 1502, H: 1142 },
            39: { L: 2812, M: 2216, Q: 1582, H: 1222 },
            40: { L: 2956, M: 2334, Q: 1666, H: 1276 }
        };

        // EC codewords per block for each version/level
        var EC_CODEWORDS = {
            1: { L: 7, M: 10, Q: 13, H: 17 },
            2: { L: 10, M: 16, Q: 22, H: 28 },
            3: { L: 15, M: 26, Q: 18, H: 22 },
            4: { L: 20, M: 18, Q: 26, H: 16 },
            5: { L: 26, M: 24, Q: 18, H: 22 },
            6: { L: 18, M: 16, Q: 24, H: 28 },
            7: { L: 20, M: 18, Q: 18, H: 26 },
            8: { L: 24, M: 22, Q: 22, H: 26 },
            9: { L: 30, M: 22, Q: 20, H: 24 },
            10: { L: 18, M: 26, Q: 24, H: 28 },
            11: { L: 20, M: 30, Q: 28, H: 24 },
            12: { L: 24, M: 22, Q: 26, H: 28 },
            13: { L: 26, M: 22, Q: 24, H: 22 },
            14: { L: 30, M: 24, Q: 20, H: 24 },
            15: { L: 22, M: 24, Q: 30, H: 24 },
            16: { L: 24, M: 28, Q: 24, H: 30 },
            17: { L: 28, M: 28, Q: 28, H: 28 },
            18: { L: 30, M: 26, Q: 28, H: 28 },
            19: { L: 28, M: 26, Q: 26, H: 26 },
            20: { L: 28, M: 26, Q: 30, H: 28 },
            21: { L: 28, M: 26, Q: 28, H: 30 },
            22: { L: 28, M: 28, Q: 30, H: 24 },
            23: { L: 30, M: 28, Q: 30, H: 30 },
            24: { L: 30, M: 28, Q: 30, H: 30 },
            25: { L: 26, M: 28, Q: 30, H: 30 },
            26: { L: 28, M: 28, Q: 28, H: 30 },
            27: { L: 30, M: 28, Q: 30, H: 30 },
            28: { L: 30, M: 28, Q: 30, H: 30 },
            29: { L: 30, M: 28, Q: 30, H: 30 },
            30: { L: 30, M: 28, Q: 30, H: 30 },
            31: { L: 30, M: 28, Q: 30, H: 30 },
            32: { L: 30, M: 28, Q: 30, H: 30 },
            33: { L: 30, M: 28, Q: 30, H: 30 },
            34: { L: 30, M: 28, Q: 30, H: 30 },
            35: { L: 30, M: 28, Q: 30, H: 30 },
            36: { L: 30, M: 28, Q: 30, H: 30 },
            37: { L: 30, M: 28, Q: 30, H: 30 },
            38: { L: 30, M: 28, Q: 30, H: 30 },
            39: { L: 30, M: 28, Q: 30, H: 30 },
            40: { L: 30, M: 28, Q: 30, H: 30 }
        };

        function selectVersion(dataLen, ecLevel) {
            for (var v = 1; v <= 40; v++) {
                if (VERSION_CAPACITY[v][ecLevel] >= dataLen) {
                    return v;
                }
            }
            throw new Error('Data too long for QR code');
        }

        function createMatrix(version) {
            var size = version * 4 + 17;
            var matrix = [];
            for (var i = 0; i < size; i++) {
                matrix[i] = new Array(size).fill(null);
            }
            return matrix;
        }

        function addFinderPattern(matrix, row, col) {
            for (var r = -1; r <= 7; r++) {
                for (var c = -1; c <= 7; c++) {
                    var tr = row + r;
                    var tc = col + c;
                    if (tr < 0 || tc < 0 || tr >= matrix.length || tc >= matrix.length) continue;

                    if (r === -1 || r === 7 || c === -1 || c === 7) {
                        matrix[tr][tc] = 0; // Separator
                    } else if ((r === 0 || r === 6) || (c === 0 || c === 6)) {
                        matrix[tr][tc] = 1;
                    } else if (r >= 2 && r <= 4 && c >= 2 && c <= 4) {
                        matrix[tr][tc] = 1;
                    } else {
                        matrix[tr][tc] = 0;
                    }
                }
            }
        }

        function addTimingPatterns(matrix) {
            var size = matrix.length;
            for (var i = 8; i < size - 8; i++) {
                var bit = (i + 1) % 2;
                if (matrix[6][i] === null) matrix[6][i] = bit;
                if (matrix[i][6] === null) matrix[i][6] = bit;
            }
        }

        function addAlignmentPattern(matrix, row, col) {
            for (var r = -2; r <= 2; r++) {
                for (var c = -2; c <= 2; c++) {
                    var tr = row + r;
                    var tc = col + c;
                    if (matrix[tr][tc] !== null) continue;

                    if (r === -2 || r === 2 || c === -2 || c === 2) {
                        matrix[tr][tc] = 1;
                    } else if (r === 0 && c === 0) {
                        matrix[tr][tc] = 1;
                    } else {
                        matrix[tr][tc] = 0;
                    }
                }
            }
        }

        var ALIGNMENT_POSITIONS = {
            2: [6, 18],
            3: [6, 22],
            4: [6, 26],
            5: [6, 30],
            6: [6, 34],
            7: [6, 22, 38],
            8: [6, 24, 42],
            9: [6, 26, 46],
            10: [6, 28, 50],
            11: [6, 30, 54],
            12: [6, 32, 58],
            13: [6, 34, 62],
            14: [6, 26, 46, 66],
            15: [6, 26, 48, 70],
            16: [6, 26, 50, 74],
            17: [6, 30, 54, 78],
            18: [6, 30, 56, 82],
            19: [6, 30, 58, 86],
            20: [6, 34, 62, 90],
            21: [6, 28, 50, 72, 94],
            22: [6, 26, 50, 74, 98],
            23: [6, 30, 54, 78, 102],
            24: [6, 28, 54, 80, 106],
            25: [6, 32, 58, 84, 110],
            26: [6, 30, 58, 86, 114],
            27: [6, 34, 62, 90, 118],
            28: [6, 26, 50, 74, 98, 122],
            29: [6, 30, 54, 78, 102, 126],
            30: [6, 26, 52, 78, 104, 130],
            31: [6, 30, 56, 82, 108, 134],
            32: [6, 34, 60, 86, 112, 138],
            33: [6, 30, 58, 86, 114, 142],
            34: [6, 34, 62, 90, 118, 146],
            35: [6, 30, 54, 78, 102, 126, 150],
            36: [6, 24, 50, 76, 102, 128, 154],
            37: [6, 28, 54, 80, 106, 132, 158],
            38: [6, 32, 58, 84, 110, 136, 162],
            39: [6, 26, 54, 82, 110, 138, 166],
            40: [6, 30, 58, 86, 114, 142, 170]
        };

        function addAlignmentPatterns(matrix, version) {
            if (version < 2) return;
            var positions = ALIGNMENT_POSITIONS[version];
            for (var i = 0; i < positions.length; i++) {
                for (var j = 0; j < positions.length; j++) {
                    var row = positions[i];
                    var col = positions[j];
                    // Skip if overlaps with finder patterns
                    if ((row < 9 && col < 9) ||
                        (row < 9 && col > matrix.length - 10) ||
                        (row > matrix.length - 10 && col < 9)) {
                        continue;
                    }
                    addAlignmentPattern(matrix, row, col);
                }
            }
        }

        function addFormatInfo(matrix, ecLevel, mask) {
            var size = matrix.length;
            var formatBits = (EC_LEVEL[ecLevel] << 3) | mask;

            // Calculate BCH(15,5) error correction
            var g = 0x537; // Generator polynomial
            var data = formatBits << 10;
            while (getBitCount(data) - getBitCount(g) >= 0) {
                data ^= g << (getBitCount(data) - getBitCount(g));
            }
            formatBits = ((formatBits << 10) | data) ^ 0x5412; // XOR with mask

            // Place format bits
            var formatPositions1 = [
                [8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5],
                [8, 7], [8, 8], [7, 8], [5, 8], [4, 8], [3, 8],
                [2, 8], [1, 8], [0, 8]
            ];
            var formatPositions2 = [];
            for (var i = size - 1; i >= size - 8; i--) {
                formatPositions2.push([8, i]);
            }
            for (var i = size - 7; i < size; i++) {
                formatPositions2.push([i, 8]);
            }

            for (var i = 0; i < 15; i++) {
                var bit = (formatBits >> i) & 1;
                var pos1 = formatPositions1[i];
                var pos2 = formatPositions2[i];
                matrix[pos1[0]][pos1[1]] = bit;
                matrix[pos2[0]][pos2[1]] = bit;
            }

            // Dark module
            matrix[size - 8][8] = 1;
        }

        function getBitCount(n) {
            var count = 0;
            while (n > 0) {
                count++;
                n >>= 1;
            }
            return count;
        }

        function addVersionInfo(matrix, version) {
            if (version < 7) return;

            var versionBits = version;
            var g = 0x1f25; // Generator polynomial for version info
            var data = version << 12;
            while (getBitCount(data) - getBitCount(g) >= 0) {
                data ^= g << (getBitCount(data) - getBitCount(g));
            }
            versionBits = (version << 12) | data;

            var size = matrix.length;
            for (var i = 0; i < 18; i++) {
                var bit = (versionBits >> i) & 1;
                var row = Math.floor(i / 3);
                var col = i % 3;
                // Bottom-left
                matrix[size - 11 + col][row] = bit;
                // Top-right
                matrix[row][size - 11 + col] = bit;
            }
        }

        function encodeData(text, version, ecLevel) {
            var data = [];

            // Mode indicator (4 bits for byte mode)
            // Character count indicator length depends on version
            var ccBits = version < 10 ? 8 : 16;

            // Convert to bytes
            var textBytes = [];
            for (var i = 0; i < text.length; i++) {
                var code = text.charCodeAt(i);
                if (code < 128) {
                    textBytes.push(code);
                } else if (code < 2048) {
                    textBytes.push(0xc0 | (code >> 6));
                    textBytes.push(0x80 | (code & 0x3f));
                } else {
                    textBytes.push(0xe0 | (code >> 12));
                    textBytes.push(0x80 | ((code >> 6) & 0x3f));
                    textBytes.push(0x80 | (code & 0x3f));
                }
            }

            var capacity = VERSION_CAPACITY[version][ecLevel];
            var ecCodewords = EC_CODEWORDS[version][ecLevel];

            // Build bit stream
            var bits = [];

            // Mode indicator
            bits.push(0, 1, 0, 0); // Byte mode

            // Character count
            for (var i = ccBits - 1; i >= 0; i--) {
                bits.push((textBytes.length >> i) & 1);
            }

            // Data
            for (var i = 0; i < textBytes.length; i++) {
                for (var j = 7; j >= 0; j--) {
                    bits.push((textBytes[i] >> j) & 1);
                }
            }

            // Terminator
            var dataCapacityBits = capacity * 8;
            var terminatorLen = Math.min(4, dataCapacityBits - bits.length);
            for (var i = 0; i < terminatorLen; i++) {
                bits.push(0);
            }

            // Pad to byte boundary
            while (bits.length % 8 !== 0) {
                bits.push(0);
            }

            // Pad bytes
            var padBytes = [0xec, 0x11];
            var padIndex = 0;
            while (bits.length < dataCapacityBits) {
                var pad = padBytes[padIndex % 2];
                for (var i = 7; i >= 0; i--) {
                    bits.push((pad >> i) & 1);
                }
                padIndex++;
            }

            // Convert to bytes
            var dataBytes = [];
            for (var i = 0; i < bits.length; i += 8) {
                var byte = 0;
                for (var j = 0; j < 8; j++) {
                    byte = (byte << 1) | bits[i + j];
                }
                dataBytes.push(byte);
            }

            // Add error correction
            var ecBytes = generateECBytes(dataBytes, ecCodewords);

            return { data: dataBytes, ec: ecBytes };
        }

        function placeData(matrix, dataBytes, ecBytes) {
            var size = matrix.length;
            var allBytes = dataBytes.concat(ecBytes);
            var bitIndex = 0;

            // Place data bits in zigzag pattern
            var upward = true;
            for (var col = size - 1; col > 0; col -= 2) {
                if (col === 6) col = 5; // Skip timing pattern column

                for (var row = upward ? size - 1 : 0;
                     upward ? row >= 0 : row < size;
                     row += upward ? -1 : 1) {

                    for (var c = 0; c < 2; c++) {
                        var actualCol = col - c;
                        if (matrix[row][actualCol] === null) {
                            if (bitIndex < allBytes.length * 8) {
                                var byteIndex = Math.floor(bitIndex / 8);
                                var bitPos = 7 - (bitIndex % 8);
                                matrix[row][actualCol] = (allBytes[byteIndex] >> bitPos) & 1;
                            } else {
                                matrix[row][actualCol] = 0;
                            }
                            bitIndex++;
                        }
                    }
                }
                upward = !upward;
            }
        }

        function applyMask(matrix, mask) {
            var size = matrix.length;
            var maskFunctions = [
                function(r, c) { return (r + c) % 2 === 0; },
                function(r, c) { return r % 2 === 0; },
                function(r, c) { return c % 3 === 0; },
                function(r, c) { return (r + c) % 3 === 0; },
                function(r, c) { return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0; },
                function(r, c) { return (r * c) % 2 + (r * c) % 3 === 0; },
                function(r, c) { return ((r * c) % 2 + (r * c) % 3) % 2 === 0; },
                function(r, c) { return ((r + c) % 2 + (r * c) % 3) % 2 === 0; }
            ];

            var fn = maskFunctions[mask];
            var result = [];
            for (var r = 0; r < size; r++) {
                result[r] = [];
                for (var c = 0; c < size; c++) {
                    result[r][c] = matrix[r][c];
                    // Only mask data/ec areas
                    if (isDataModule(matrix, r, c, size)) {
                        if (fn(r, c)) {
                            result[r][c] = matrix[r][c] ^ 1;
                        }
                    }
                }
            }
            return result;
        }

        function isDataModule(matrix, row, col, size) {
            // Check if this module is in a function pattern area
            // Finder patterns
            if (row < 9 && col < 9) return false;
            if (row < 9 && col > size - 9) return false;
            if (row > size - 9 && col < 9) return false;
            // Timing patterns
            if (row === 6 || col === 6) return false;
            return true;
        }

        function calculatePenalty(matrix) {
            // Simplified penalty calculation
            var penalty = 0;
            var size = matrix.length;

            // Rule 1: Adjacent modules in row/column
            for (var r = 0; r < size; r++) {
                var count = 1;
                for (var c = 1; c < size; c++) {
                    if (matrix[r][c] === matrix[r][c-1]) {
                        count++;
                    } else {
                        if (count >= 5) penalty += 3 + (count - 5);
                        count = 1;
                    }
                }
                if (count >= 5) penalty += 3 + (count - 5);
            }

            for (var c = 0; c < size; c++) {
                var count = 1;
                for (var r = 1; r < size; r++) {
                    if (matrix[r][c] === matrix[r-1][c]) {
                        count++;
                    } else {
                        if (count >= 5) penalty += 3 + (count - 5);
                        count = 1;
                    }
                }
                if (count >= 5) penalty += 3 + (count - 5);
            }

            return penalty;
        }

        function generate(text, options) {
            options = options || {};
            var ecLevel = options.ecLevel || 'M';

            // Select version based on data length
            var textLen = 0;
            for (var i = 0; i < text.length; i++) {
                var code = text.charCodeAt(i);
                if (code < 128) textLen += 1;
                else if (code < 2048) textLen += 2;
                else textLen += 3;
            }

            var version = selectVersion(textLen + 3, ecLevel); // +3 for mode/count indicators
            var size = version * 4 + 17;

            // Create matrix with function patterns
            var matrix = createMatrix(version);

            // Add finder patterns
            addFinderPattern(matrix, 0, 0);
            addFinderPattern(matrix, 0, size - 7);
            addFinderPattern(matrix, size - 7, 0);

            // Add timing patterns
            addTimingPatterns(matrix);

            // Add alignment patterns
            addAlignmentPatterns(matrix, version);

            // Encode data
            var encoded = encodeData(text, version, ecLevel);

            // Place data
            placeData(matrix, encoded.data, encoded.ec);

            // Find best mask
            var bestMask = 0;
            var bestPenalty = Infinity;
            var bestMatrix = null;

            for (var mask = 0; mask < 8; mask++) {
                var masked = applyMask(matrix, mask);
                addFormatInfo(masked, ecLevel, mask);
                addVersionInfo(masked, version);
                var penalty = calculatePenalty(masked);
                if (penalty < bestPenalty) {
                    bestPenalty = penalty;
                    bestMask = mask;
                    bestMatrix = masked;
                }
            }

            return {
                matrix: bestMatrix,
                version: version,
                size: size
            };
        }

        function toDataURL(text, options) {
            options = options || {};
            var scale = options.scale || 4;
            var margin = options.margin || 4;
            var dark = options.dark || '#000000';
            var light = options.light || '#ffffff';

            var qr = generate(text, options);
            var size = qr.size;
            var canvasSize = (size + margin * 2) * scale;

            // Create canvas
            var canvas = document.createElement('canvas');
            canvas.width = canvasSize;
            canvas.height = canvasSize;
            var ctx = canvas.getContext('2d');

            // Fill background
            ctx.fillStyle = light;
            ctx.fillRect(0, 0, canvasSize, canvasSize);

            // Draw modules
            ctx.fillStyle = dark;
            for (var r = 0; r < size; r++) {
                for (var c = 0; c < size; c++) {
                    if (qr.matrix[r][c] === 1) {
                        ctx.fillRect(
                            (c + margin) * scale,
                            (r + margin) * scale,
                            scale,
                            scale
                        );
                    }
                }
            }

            return canvas.toDataURL('image/png');
        }

        function toSVG(text, options) {
            options = options || {};
            var scale = options.scale || 4;
            var margin = options.margin || 4;
            var dark = options.dark || '#000000';
            var light = options.light || '#ffffff';

            var qr = generate(text, options);
            var size = qr.size;
            var svgSize = (size + margin * 2) * scale;

            var paths = [];
            for (var r = 0; r < size; r++) {
                for (var c = 0; c < size; c++) {
                    if (qr.matrix[r][c] === 1) {
                        var x = (c + margin) * scale;
                        var y = (r + margin) * scale;
                        paths.push('M' + x + ',' + y + 'h' + scale + 'v' + scale + 'h-' + scale + 'z');
                    }
                }
            }

            return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + svgSize + ' ' + svgSize + '" width="' + svgSize + '" height="' + svgSize + '">' +
                '<rect width="100%" height="100%" fill="' + light + '"/>' +
                '<path d="' + paths.join('') + '" fill="' + dark + '"/>' +
                '</svg>';
        }

        return {
            generate: generate,
            toDataURL: toDataURL,
            toSVG: toSVG
        };
    })();


    // ============================================================
    // SDK Configuration
    // ============================================================

    var config = {
        clientToken: null,
        apiBase: null,
        // Legacy theme colors (for backward compatibility)
        theme: {
            primaryColor: '#2563eb',
            backgroundColor: '#ffffff',
            textColor: '#1f2937',
            borderRadius: '12px',
            fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif'
        },
        // Class-based theme (Tailwind or custom CSS classes)
        classTheme: null,
        pollInterval: 2000,
        timeout: 300000
    };

    // Default class-based theme (used when classTheme is set)
    var DEFAULT_CLASS_THEME = {
        overlay: 'fixed inset-0 bg-black/50 flex items-center justify-center z-[999999]',
        modal: 'bg-white rounded-xl shadow-2xl max-w-md w-[90%] max-h-[90vh] overflow-auto relative',
        header: 'px-6 py-5 border-b border-gray-200 flex items-center justify-between',
        content: 'p-6 text-center',
        footer: 'px-6 py-4 border-t border-gray-200 text-center',
        title: 'text-lg font-semibold text-gray-900 m-0',
        subtitle: 'text-base text-gray-900 mb-6 leading-relaxed',
        footerText: 'text-xs text-gray-400',
        statusText: 'text-sm text-gray-500 mb-2',
        qrContainer: 'bg-white p-4 rounded-lg inline-block mb-5 shadow-sm border border-gray-100',
        primaryButton: 'w-full inline-block bg-blue-600 hover:bg-blue-700 text-white py-3 px-6 rounded-lg text-base font-medium cursor-pointer border-0 transition-colors duration-200',
        secondaryButton: 'bg-transparent border-0 text-gray-500 text-sm cursor-pointer mt-3 hover:text-gray-700',
        closeButton: 'bg-transparent border-0 cursor-pointer p-2 text-gray-400 text-2xl leading-none hover:text-gray-600',
        deepLinkButton: 'w-full inline-block bg-blue-600 hover:bg-blue-700 text-white py-3 px-6 rounded-lg text-base font-medium no-underline cursor-pointer border-0 transition-colors duration-200 box-border',
        divider: 'flex items-center my-5 text-gray-400',
        dividerLine: 'flex-1 h-px bg-gray-200',
        dividerText: 'px-3 text-sm',
        pendingStatus: 'text-sm text-gray-500',
        successStatus: 'text-sm text-green-600 font-medium',
        errorStatus: 'text-sm text-red-600 font-medium',
        successIcon: 'w-16 h-16 bg-green-500 rounded-full flex items-center justify-center mx-auto mb-5',
        errorIcon: 'w-16 h-16 bg-red-500 rounded-full flex items-center justify-center mx-auto mb-5',
        spinner: 'w-6 h-6 border-3 border-gray-200 border-t-blue-600 rounded-full animate-spin mx-auto mb-4',
        inlineContainer: 'bg-white rounded-xl border border-gray-200 p-6 text-center',
        resultSummary: 'mt-4 p-3 bg-gray-50 rounded-lg text-left text-sm'
    };

    var initialized = false;
    var activeModal = null;
    var activeContainer = null;
    var activeSession = null;
    var pollTimer = null;


    // ============================================================
    // Utility Functions
    // ============================================================

    function log(level, message, data) {
        if (typeof console !== 'undefined' && console[level]) {
            if (data) {
                console[level]('[WaltVerify]', message, data);
            } else {
                console[level]('[WaltVerify]', message);
            }
        }
    }

    function mergeDeep(target, source) {
        var output = Object.assign({}, target);
        if (isObject(target) && isObject(source)) {
            Object.keys(source).forEach(function(key) {
                if (isObject(source[key])) {
                    if (!(key in target)) {
                        Object.assign(output, defineProperty({}, key, source[key]));
                    } else {
                        output[key] = mergeDeep(target[key], source[key]);
                    }
                } else {
                    Object.assign(output, defineProperty({}, key, source[key]));
                }
            });
        }
        return output;
    }

    function isObject(item) {
        return item && typeof item === 'object' && !Array.isArray(item);
    }

    function defineProperty(obj, key, value) {
        obj[key] = value;
        return obj;
    }

    function detectApiBase() {
        // Try to detect from script src
        var scripts = document.getElementsByTagName('script');
        for (var i = 0; i < scripts.length; i++) {
            var src = scripts[i].src || '';
            if (src.indexOf('/widget/v1/sdk.js') !== -1 || src.indexOf('/widget/sdk.js') !== -1) {
                var url = new URL(src);
                return url.origin;
            }
        }
        // Default to current origin
        return window.location.origin;
    }

    /**
     * Check if class-based theming is enabled.
     * @returns {boolean}
     */
    function useClassTheme() {
        return config.classTheme !== null;
    }

    /**
     * Get the current class theme with defaults.
     * @returns {Object}
     */
    function getClassTheme() {
        if (!config.classTheme) return DEFAULT_CLASS_THEME;
        return mergeDeep(DEFAULT_CLASS_THEME, config.classTheme);
    }

    /**
     * Apply classes to an element (for class-based theming).
     * @param {HTMLElement} element
     * @param {string} classes - Space-separated class names
     */
    function applyClasses(element, classes) {
        if (!classes) return;
        var classList = classes.split(' ').filter(function(c) { return c.trim(); });
        for (var i = 0; i < classList.length; i++) {
            element.classList.add(classList[i]);
        }
    }


    // ============================================================
    // Styles
    // ============================================================

    var STYLES = {
        overlay: function(t) {
            return {
                position: 'fixed',
                top: '0',
                left: '0',
                right: '0',
                bottom: '0',
                backgroundColor: 'rgba(0, 0, 0, 0.5)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                zIndex: '999999',
                fontFamily: t.fontFamily
            };
        },
        modal: function(t) {
            return {
                backgroundColor: t.backgroundColor,
                borderRadius: t.borderRadius,
                boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)',
                maxWidth: '420px',
                width: '90%',
                maxHeight: '90vh',
                overflow: 'auto',
                position: 'relative'
            };
        },
        header: function(t) {
            return {
                padding: '20px 24px',
                borderBottom: '1px solid #e5e7eb',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between'
            };
        },
        title: function(t) {
            return {
                fontSize: '18px',
                fontWeight: '600',
                color: t.textColor,
                margin: '0'
            };
        },
        closeButton: function(t) {
            return {
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                padding: '8px',
                color: '#6b7280',
                fontSize: '24px',
                lineHeight: '1'
            };
        },
        content: function(t) {
            return {
                padding: '24px',
                textAlign: 'center'
            };
        },
        qrContainer: function(t) {
            return {
                backgroundColor: '#ffffff',
                padding: '16px',
                borderRadius: '8px',
                display: 'inline-block',
                marginBottom: '20px'
            };
        },
        statusText: function(t) {
            return {
                fontSize: '14px',
                color: '#6b7280',
                marginBottom: '8px'
            };
        },
        instructionText: function(t) {
            return {
                fontSize: '16px',
                color: t.textColor,
                marginBottom: '24px',
                lineHeight: '1.5'
            };
        },
        deepLinkButton: function(t) {
            return {
                display: 'inline-block',
                backgroundColor: t.primaryColor,
                color: '#ffffff',
                padding: '12px 32px',
                borderRadius: '8px',
                textDecoration: 'none',
                fontSize: '15px',
                fontWeight: '500',
                marginBottom: '16px',
                border: 'none',
                cursor: 'pointer',
                boxSizing: 'border-box',
                maxWidth: '320px',
                width: '100%'
            };
        },
        divider: function(t) {
            return {
                display: 'flex',
                alignItems: 'center',
                margin: '20px 0',
                color: '#9ca3af',
                width: '100%',
                maxWidth: '320px'
            };
        },
        dividerLine: function() {
            return {
                flex: '1',
                height: '1px',
                backgroundColor: '#e5e7eb'
            };
        },
        dividerText: function() {
            return {
                padding: '0 12px',
                fontSize: '14px'
            };
        },
        successIcon: function() {
            return {
                width: '56px',
                height: '56px',
                backgroundColor: '#10b981',
                borderRadius: '50%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                margin: '0 auto 16px',
                flexShrink: '0'
            };
        },
        errorIcon: function() {
            return {
                width: '56px',
                height: '56px',
                backgroundColor: '#ef4444',
                borderRadius: '50%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                margin: '0 auto 16px',
                flexShrink: '0'
            };
        },
        spinner: function() {
            return {
                width: '24px',
                height: '24px',
                border: '3px solid #e5e7eb',
                borderTopColor: '#2563eb',
                borderRadius: '50%',
                animation: 'waltverify-spin 1s linear infinite',
                margin: '0 auto 16px'
            };
        },
        inlineContainer: function(t) {
            return {
                backgroundColor: t.backgroundColor,
                borderRadius: t.borderRadius,
                border: '1px solid #e5e7eb',
                padding: '24px',
                textAlign: 'center',
                fontFamily: t.fontFamily,
                maxWidth: '480px',
                margin: '0 auto',
                boxSizing: 'border-box'
            };
        },
        footer: function(t) {
            return {
                padding: '16px 24px',
                borderTop: '1px solid #e5e7eb',
                textAlign: 'center'
            };
        },
        footerText: function() {
            return {
                fontSize: '12px',
                color: '#9ca3af'
            };
        },
        footerLink: function() {
            return {
                color: '#6b7280',
                textDecoration: 'none'
            };
        }
    };

    function applyStyles(element, styles) {
        Object.keys(styles).forEach(function(key) {
            element.style[key] = styles[key];
        });
    }

    function injectGlobalStyles() {
        if (document.getElementById('waltverify-styles')) return;

        var style = document.createElement('style');
        style.id = 'waltverify-styles';
        style.textContent = [
            '@keyframes waltverify-spin {',
            '  from { transform: rotate(0deg); }',
            '  to { transform: rotate(360deg); }',
            '}',
            '@keyframes waltverify-fade-in {',
            '  from { opacity: 0; transform: scale(0.95); }',
            '  to { opacity: 1; transform: scale(1); }',
            '}',
            '.waltverify-modal-enter {',
            '  animation: waltverify-fade-in 0.2s ease-out forwards;',
            '}'
        ].join('\n');
        document.head.appendChild(style);
    }


    // ============================================================
    // API Functions
    // ============================================================

    function apiRequest(method, path, body) {
        var url = config.apiBase + path;
        var headers = {
            'Content-Type': 'application/json'
        };

        if (config.clientToken) {
            headers['Authorization'] = 'Bearer ' + config.clientToken;
        }

        return fetch(url, {
            method: method,
            headers: headers,
            body: body ? JSON.stringify(body) : undefined
        }).then(function(response) {
            return response.json().then(function(data) {
                if (!response.ok) {
                    var error = new Error(data.error || 'API request failed');
                    error.code = data.code;
                    error.status = response.status;
                    throw error;
                }
                return data;
            });
        });
    }

    function createSession(options) {
        return apiRequest('POST', '/widget/v1/verify', {
            template: options.template,
            response_mode: options.responseMode || 'answers',
            redirect_uri: options.redirectUri,
            metadata: options.metadata
        });
    }

    function getSessionStatus(sessionId) {
        return apiRequest('GET', '/widget/v1/sessions/' + encodeURIComponent(sessionId));
    }


    // ============================================================
    // UI Components
    // ============================================================

    function createModal(options) {
        var theme = config.theme;
        var classTheme = useClassTheme() ? getClassTheme() : null;
        injectGlobalStyles();

        // Create overlay
        var overlay = document.createElement('div');
        overlay.className = 'waltverify-overlay';
        if (classTheme) {
            applyClasses(overlay, classTheme.overlay);
        } else {
            applyStyles(overlay, STYLES.overlay(theme));
        }

        // Close on overlay click (outside modal)
        overlay.addEventListener('click', function(e) {
            if (e.target === overlay) {
                closeModal(options.onCancel);
            }
        });

        // Create modal
        var modal = document.createElement('div');
        modal.className = 'waltverify-modal waltverify-modal-enter';
        if (classTheme) {
            applyClasses(modal, classTheme.modal);
        } else {
            applyStyles(modal, STYLES.modal(theme));
        }

        // Header
        var header = document.createElement('div');
        if (classTheme) {
            applyClasses(header, classTheme.header);
        } else {
            applyStyles(header, STYLES.header(theme));
        }

        var title = document.createElement('h2');
        if (classTheme) {
            applyClasses(title, classTheme.title);
        } else {
            applyStyles(title, STYLES.title(theme));
        }
        title.textContent = options.title || 'Identity Verification';

        var closeBtn = document.createElement('button');
        if (classTheme) {
            applyClasses(closeBtn, classTheme.closeButton);
        } else {
            applyStyles(closeBtn, STYLES.closeButton(theme));
        }
        closeBtn.innerHTML = '&times;';
        closeBtn.setAttribute('aria-label', 'Close');
        closeBtn.addEventListener('click', function() {
            closeModal(options.onCancel);
        });

        header.appendChild(title);
        header.appendChild(closeBtn);
        modal.appendChild(header);

        // Content area
        var content = document.createElement('div');
        content.className = 'waltverify-content';
        if (classTheme) {
            applyClasses(content, classTheme.content);
        } else {
            applyStyles(content, STYLES.content(theme));
        }
        modal.appendChild(content);

        // Footer
        var footer = document.createElement('div');
        if (classTheme) {
            applyClasses(footer, classTheme.footer);
        } else {
            applyStyles(footer, STYLES.footer(theme));
        }
        var footerText = document.createElement('span');
        if (classTheme) {
            applyClasses(footerText, classTheme.footerText);
        } else {
            applyStyles(footerText, STYLES.footerText());
        }
        footerText.innerHTML = 'Powered by <a href="https://walt.id" target="_blank" rel="noopener" style="color: inherit; text-decoration: none;">walt.id</a>';
        footer.appendChild(footerText);
        modal.appendChild(footer);

        overlay.appendChild(modal);

        return {
            overlay: overlay,
            modal: modal,
            content: content,
            header: header,
            title: title,
            footer: footer
        };
    }

    function renderQRContent(container, session, options) {
        var theme = config.theme;
        var classTheme = useClassTheme() ? getClassTheme() : null;
        container.innerHTML = '';

        // Force vertical centered layout regardless of parent styles
        if (!classTheme) {
            container.style.display = 'flex';
            container.style.flexDirection = 'column';
            container.style.alignItems = 'center';
        }

        // Instruction text
        var instruction = document.createElement('p');
        if (classTheme) {
            applyClasses(instruction, classTheme.subtitle);
        } else {
            applyStyles(instruction, STYLES.instructionText(theme));
        }
        instruction.textContent = options.instruction || 'Scan the QR code with your wallet app to verify your identity';
        container.appendChild(instruction);

        // QR code container
        var qrWrapper = document.createElement('div');
        if (classTheme) {
            applyClasses(qrWrapper, classTheme.qrContainer);
        } else {
            applyStyles(qrWrapper, STYLES.qrContainer(theme));
        }

        // Use server-generated QR code image from session response
        var qrImg = document.createElement('img');
        qrImg.src = session.qr_code_image;
        qrImg.alt = 'Scan QR code';
        qrImg.style.width = '200px';
        qrImg.style.height = '200px';
        qrWrapper.appendChild(qrImg);
        container.appendChild(qrWrapper);

        // Divider
        var divider = document.createElement('div');
        if (classTheme) {
            applyClasses(divider, classTheme.divider);
        } else {
            applyStyles(divider, STYLES.divider(theme));
        }
        var line1 = document.createElement('div');
        if (classTheme) {
            applyClasses(line1, classTheme.dividerLine);
        } else {
            applyStyles(line1, STYLES.dividerLine());
        }
        var dividerText = document.createElement('span');
        if (classTheme) {
            applyClasses(dividerText, classTheme.dividerText);
        } else {
            applyStyles(dividerText, STYLES.dividerText());
        }
        dividerText.textContent = 'or';
        var line2 = document.createElement('div');
        if (classTheme) {
            applyClasses(line2, classTheme.dividerLine);
        } else {
            applyStyles(line2, STYLES.dividerLine());
        }
        divider.appendChild(line1);
        divider.appendChild(dividerText);
        divider.appendChild(line2);
        container.appendChild(divider);

        // Deep link button
        var deepLinkBtn = document.createElement('a');
        if (classTheme) {
            applyClasses(deepLinkBtn, classTheme.deepLinkButton);
        } else {
            applyStyles(deepLinkBtn, STYLES.deepLinkButton(theme));
        }
        deepLinkBtn.href = session.deep_link;
        deepLinkBtn.textContent = 'Open Wallet App';
        container.appendChild(deepLinkBtn);

        // Status indicator
        var statusContainer = document.createElement('div');
        statusContainer.style.marginTop = '20px';

        var spinner = document.createElement('div');
        if (classTheme) {
            applyClasses(spinner, classTheme.spinner);
        } else {
            applyStyles(spinner, STYLES.spinner());
        }
        statusContainer.appendChild(spinner);

        var statusText = document.createElement('p');
        if (classTheme) {
            applyClasses(statusText, classTheme.pendingStatus);
        } else {
            applyStyles(statusText, STYLES.statusText(theme));
        }
        statusText.textContent = 'Waiting for verification...';
        statusText.className = 'waltverify-status-text';
        statusContainer.appendChild(statusText);

        container.appendChild(statusContainer);
    }

    function renderSuccessContent(container, result, options) {
        var theme = config.theme;
        var classTheme = useClassTheme() ? getClassTheme() : null;
        container.innerHTML = '';

        // Force vertical centered layout regardless of parent styles
        if (!classTheme) {
            container.style.display = 'flex';
            container.style.flexDirection = 'column';
            container.style.alignItems = 'center';
        }

        // Success icon
        var iconWrapper = document.createElement('div');
        if (classTheme) {
            applyClasses(iconWrapper, classTheme.successIcon);
        } else {
            applyStyles(iconWrapper, STYLES.successIcon());
        }
        iconWrapper.innerHTML = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';
        container.appendChild(iconWrapper);

        // Success message
        var message = document.createElement('h3');
        if (classTheme) {
            applyClasses(message, classTheme.title);
            message.classList.add('mb-2');
        } else {
            message.style.fontSize = '20px';
            message.style.fontWeight = '600';
            message.style.color = theme.textColor;
            message.style.marginBottom = '4px';
            message.style.marginTop = '0';
        }
        message.textContent = options.successTitle || 'Verification Successful';
        container.appendChild(message);

        var subtext = document.createElement('p');
        if (classTheme) {
            applyClasses(subtext, classTheme.successStatus);
        } else {
            applyStyles(subtext, STYLES.statusText(theme));
            subtext.style.marginTop = '0';
        }
        subtext.textContent = options.successMessage || 'Your identity has been verified.';
        container.appendChild(subtext);

        // Show result summary if available
        if (result.result && result.result.answers) {
            var summaryDiv = document.createElement('div');
            if (classTheme) {
                applyClasses(summaryDiv, classTheme.resultSummary);
            } else {
                summaryDiv.style.marginTop = '16px';
                summaryDiv.style.padding = '12px 16px';
                summaryDiv.style.backgroundColor = '#f3f4f6';
                summaryDiv.style.borderRadius = '8px';
                summaryDiv.style.textAlign = 'left';
                summaryDiv.style.width = '100%';
                summaryDiv.style.maxWidth = '320px';
                summaryDiv.style.boxSizing = 'border-box';
            }

            Object.keys(result.result.answers).forEach(function(key) {
                var item = document.createElement('div');
                item.style.fontSize = '14px';
                item.style.marginBottom = '4px';
                var label = document.createElement('span');
                label.style.color = '#6b7280';
                label.textContent = key + ': ';
                var value = document.createElement('span');
                value.style.color = classTheme ? 'inherit' : theme.textColor;
                value.style.fontWeight = '500';
                value.textContent = result.result.answers[key];
                item.appendChild(label);
                item.appendChild(value);
                summaryDiv.appendChild(item);
            });

            container.appendChild(summaryDiv);
        }

        // Close button
        var closeBtn = document.createElement('button');
        if (classTheme) {
            applyClasses(closeBtn, classTheme.primaryButton);
            closeBtn.classList.add('mt-5');
        } else {
            closeBtn.style.display = 'inline-block';
            closeBtn.style.backgroundColor = theme.primaryColor;
            closeBtn.style.color = '#ffffff';
            closeBtn.style.padding = '10px 32px';
            closeBtn.style.borderRadius = '8px';
            closeBtn.style.fontSize = '15px';
            closeBtn.style.fontWeight = '500';
            closeBtn.style.border = 'none';
            closeBtn.style.cursor = 'pointer';
            closeBtn.style.marginTop = '20px';
        }
        closeBtn.textContent = 'Done';
        closeBtn.addEventListener('click', function() {
            closeModal();
        });
        container.appendChild(closeBtn);
    }

    function renderErrorContent(container, error, options) {
        var theme = config.theme;
        var classTheme = useClassTheme() ? getClassTheme() : null;
        container.innerHTML = '';

        // Force vertical centered layout regardless of parent styles
        if (!classTheme) {
            container.style.display = 'flex';
            container.style.flexDirection = 'column';
            container.style.alignItems = 'center';
        }

        // Error icon
        var iconWrapper = document.createElement('div');
        if (classTheme) {
            applyClasses(iconWrapper, classTheme.errorIcon);
        } else {
            applyStyles(iconWrapper, STYLES.errorIcon());
        }
        iconWrapper.innerHTML = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>';
        container.appendChild(iconWrapper);

        // Error message
        var message = document.createElement('h3');
        if (classTheme) {
            applyClasses(message, classTheme.title);
            message.classList.add('mb-2');
        } else {
            message.style.fontSize = '20px';
            message.style.fontWeight = '600';
            message.style.color = theme.textColor;
            message.style.marginBottom = '4px';
            message.style.marginTop = '0';
        }
        message.textContent = options.errorTitle || 'Verification Failed';
        container.appendChild(message);

        var subtext = document.createElement('p');
        if (classTheme) {
            applyClasses(subtext, classTheme.errorStatus);
        } else {
            applyStyles(subtext, STYLES.statusText(theme));
            subtext.style.marginTop = '0';
        }
        subtext.textContent = error.message || options.errorMessage || 'We could not verify your identity.';
        container.appendChild(subtext);

        // Retry button
        var retryBtn = document.createElement('button');
        if (classTheme) {
            applyClasses(retryBtn, classTheme.primaryButton);
            retryBtn.classList.add('mt-5');
        } else {
            retryBtn.style.display = 'inline-block';
            retryBtn.style.backgroundColor = theme.primaryColor;
            retryBtn.style.color = '#ffffff';
            retryBtn.style.padding = '10px 32px';
            retryBtn.style.borderRadius = '8px';
            retryBtn.style.fontSize = '15px';
            retryBtn.style.fontWeight = '500';
            retryBtn.style.border = 'none';
            retryBtn.style.cursor = 'pointer';
            retryBtn.style.marginTop = '20px';
        }
        retryBtn.textContent = 'Try Again';
        retryBtn.addEventListener('click', function() {
            if (options.onRetry) {
                options.onRetry();
            } else {
                closeModal();
            }
        });
        container.appendChild(retryBtn);

        // Cancel link
        var cancelBtn = document.createElement('button');
        if (classTheme) {
            applyClasses(cancelBtn, classTheme.secondaryButton);
        } else {
            cancelBtn.style.background = 'none';
            cancelBtn.style.border = 'none';
            cancelBtn.style.color = '#6b7280';
            cancelBtn.style.fontSize = '14px';
            cancelBtn.style.cursor = 'pointer';
            cancelBtn.style.marginTop = '12px';
        }
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', function() {
            closeModal(options.onCancel);
        });
        container.appendChild(cancelBtn);
    }

    function updateStatusText(text) {
        var statusEl = document.querySelector('.waltverify-status-text');
        if (statusEl) {
            statusEl.textContent = text;
        }
    }


    // ============================================================
    // Polling Logic
    // ============================================================

    function startPolling(sessionId, options) {
        var startTime = Date.now();
        var timeout = options.timeout || config.timeout;

        function poll() {
            if (Date.now() - startTime > timeout) {
                stopPolling();
                var error = new Error('Verification timeout');
                error.code = 'TIMEOUT';
                if (activeContainer) {
                    renderErrorContent(activeContainer, error, options);
                }
                if (options.onFailure) {
                    options.onFailure(error);
                }
                return;
            }

            getSessionStatus(sessionId).then(function(status) {
                log('debug', 'Session status:', status);

                if (status.status === 'verified') {
                    stopPolling();
                    if (activeContainer) {
                        renderSuccessContent(activeContainer, status, options);
                    }
                    if (options.onSuccess) {
                        options.onSuccess({
                            sessionId: status.session_id,
                            status: status.status,
                            result: status.result,
                            verifiedAt: status.verified_at
                        });
                    }
                } else if (status.status === 'failed') {
                    stopPolling();
                    var error = new Error('Verification failed');
                    error.code = 'VERIFICATION_FAILED';
                    if (activeContainer) {
                        renderErrorContent(activeContainer, error, options);
                    }
                    if (options.onFailure) {
                        options.onFailure(error);
                    }
                } else if (status.status === 'expired') {
                    stopPolling();
                    var error = new Error('Session expired');
                    error.code = 'SESSION_EXPIRED';
                    if (activeContainer) {
                        renderErrorContent(activeContainer, error, options);
                    }
                    if (options.onFailure) {
                        options.onFailure(error);
                    }
                } else {
                    // Still pending - update status and continue polling
                    if (status.status === 'pending') {
                        updateStatusText('Waiting for verification...');
                    } else if (status.status === 'processing') {
                        updateStatusText('Processing verification...');
                    }
                    pollTimer = setTimeout(poll, config.pollInterval);
                }
            }).catch(function(error) {
                log('error', 'Error polling session status:', error);
                // Continue polling on transient errors
                pollTimer = setTimeout(poll, config.pollInterval);
            });
        }

        poll();
    }

    function stopPolling() {
        if (pollTimer) {
            clearTimeout(pollTimer);
            pollTimer = null;
        }
    }


    // ============================================================
    // Modal Management
    // ============================================================

    function closeModal(callback) {
        stopPolling();
        activeContainer = null;
        if (activeModal) {
            document.body.removeChild(activeModal.overlay);
            activeModal = null;
            activeSession = null;
        }
        if (callback) {
            callback();
        }
    }


    // ============================================================
    // Public API
    // ============================================================

    var WaltVerify = {
        /**
         * Initialize the SDK with configuration.
         *
         * @param {Object} options - Configuration options
         * @param {string} options.clientToken - Client token (ct_xxx) from your backend
         * @param {string} [options.apiBase] - API base URL (auto-detected if not provided)
         * @param {Object} [options.theme] - Theme customization (legacy inline styles)
         * @param {string} [options.theme.primaryColor] - Primary button color
         * @param {string} [options.theme.backgroundColor] - Modal background color
         * @param {string} [options.theme.textColor] - Text color
         * @param {string} [options.theme.borderRadius] - Border radius for modal/buttons
         * @param {string} [options.theme.fontFamily] - Font family
         * @param {Object} [options.classTheme] - Class-based theme (Tailwind/CSS)
         * @param {string} [options.classTheme.overlay] - Modal overlay classes
         * @param {string} [options.classTheme.modal] - Modal container classes
         * @param {string} [options.classTheme.header] - Modal header classes
         * @param {string} [options.classTheme.title] - Title text classes
         * @param {string} [options.classTheme.subtitle] - Subtitle text classes
         * @param {string} [options.classTheme.content] - Content area classes
         * @param {string} [options.classTheme.footer] - Footer container classes
         * @param {string} [options.classTheme.footerText] - Footer text classes
         * @param {string} [options.classTheme.qrContainer] - QR code container classes
         * @param {string} [options.classTheme.primaryButton] - Primary button classes
         * @param {string} [options.classTheme.secondaryButton] - Secondary button classes
         * @param {string} [options.classTheme.closeButton] - Close button classes
         * @param {string} [options.classTheme.deepLinkButton] - Deep link button classes
         * @param {string} [options.classTheme.divider] - Divider container classes
         * @param {string} [options.classTheme.dividerLine] - Divider line classes
         * @param {string} [options.classTheme.dividerText] - Divider text classes
         * @param {string} [options.classTheme.pendingStatus] - Pending status classes
         * @param {string} [options.classTheme.successStatus] - Success status classes
         * @param {string} [options.classTheme.errorStatus] - Error status classes
         * @param {string} [options.classTheme.successIcon] - Success icon wrapper classes
         * @param {string} [options.classTheme.errorIcon] - Error icon wrapper classes
         * @param {string} [options.classTheme.spinner] - Loading spinner classes
         * @param {string} [options.classTheme.inlineContainer] - Inline mode container classes
         * @param {string} [options.classTheme.resultSummary] - Result summary classes
         */
        init: function(options) {
            if (!options || !options.clientToken) {
                throw new Error('WaltVerify.init: clientToken is required');
            }

            if (!options.clientToken.startsWith('ct_')) {
                throw new Error('WaltVerify.init: clientToken must start with "ct_"');
            }

            config.clientToken = options.clientToken;
            config.apiBase = options.apiBase || detectApiBase();

            // Legacy inline style theme
            if (options.theme) {
                config.theme = mergeDeep(config.theme, options.theme);
            }

            // Class-based theme (takes precedence over inline styles)
            if (options.classTheme) {
                config.classTheme = options.classTheme;
            }

            if (options.pollInterval) {
                config.pollInterval = options.pollInterval;
            }

            if (options.timeout) {
                config.timeout = options.timeout;
            }

            initialized = true;
            log('info', 'WaltVerify initialized', {
                apiBase: config.apiBase,
                themeMode: config.classTheme ? 'class-based' : 'inline-styles'
            });
        },

        /**
         * Check if the SDK has been initialized.
         *
         * @returns {boolean} True if initialized
         */
        isInitialized: function() {
            return initialized;
        },

        /**
         * Convenience method for age verification.
         *
         * @param {Object} options - Verification options
         * @param {number} options.minAge - Minimum age required
         * @param {string} [options.ui='modal'] - UI mode: 'modal', 'inline', or 'redirect'
         * @param {string|HTMLElement} [options.container] - Container for inline mode
         * @param {Function} [options.onSuccess] - Success callback with result
         * @param {Function} [options.onFailure] - Failure callback with error
         * @param {Function} [options.onCancel] - Cancel callback
         * @param {Object} [options.metadata] - Additional metadata
         * @returns {Promise} Resolves with verification result
         */
        verifyAge: function(options) {
            if (!options || typeof options.minAge !== 'number') {
                return Promise.reject(new Error('WaltVerify.verifyAge: minAge is required'));
            }

            return this.verify({
                template: 'age_over_' + options.minAge,
                ui: options.ui || 'modal',
                container: options.container,
                onSuccess: options.onSuccess,
                onFailure: options.onFailure,
                onCancel: options.onCancel,
                metadata: options.metadata,
                title: 'Age Verification',
                instruction: 'Scan the QR code to verify you are ' + options.minAge + ' or older',
                successTitle: 'Age Verified',
                successMessage: 'You have confirmed you are ' + options.minAge + ' or older.'
            });
        },

        /**
         * Start a verification flow using a template.
         *
         * @param {Object} options - Verification options
         * @param {string} options.template - Template name
         * @param {string} [options.ui='modal'] - UI mode: 'modal', 'inline', or 'redirect'
         * @param {string|HTMLElement} [options.container] - Container for inline mode
         * @param {string} [options.responseMode='answers'] - Response mode: 'answers' or 'raw_credentials'
         * @param {string} [options.redirectUri] - Redirect URI for same-device flow
         * @param {Function} [options.onSuccess] - Success callback with result
         * @param {Function} [options.onFailure] - Failure callback with error
         * @param {Function} [options.onCancel] - Cancel callback
         * @param {Object} [options.metadata] - Additional metadata
         * @param {string} [options.title] - Modal title
         * @param {string} [options.instruction] - Instruction text
         * @returns {Promise} Resolves with verification result
         */
        verify: function(options) {
            var self = this;

            if (!initialized) {
                return Promise.reject(new Error('WaltVerify: SDK not initialized. Call WaltVerify.init() first.'));
            }

            if (!options || !options.template) {
                return Promise.reject(new Error('WaltVerify.verify: template is required'));
            }

            var ui = options.ui || 'modal';

            return new Promise(function(resolve, reject) {
                // Create verification session
                createSession({
                    template: options.template,
                    responseMode: options.responseMode || 'answers',
                    redirectUri: options.redirectUri,
                    metadata: options.metadata
                }).then(function(session) {
                    log('info', 'Session created:', session.session_id);
                    activeSession = session;

                    // Handle different UI modes
                    if (ui === 'redirect') {
                        // Redirect to wallet
                        window.location.href = session.deep_link;
                        return;
                    }

                    if (ui === 'inline') {
                        var container = typeof options.container === 'string'
                            ? document.querySelector(options.container)
                            : options.container;

                        if (!container) {
                            throw new Error('WaltVerify.verify: container not found for inline mode');
                        }

                        var classTheme = useClassTheme() ? getClassTheme() : null;
                        if (classTheme) {
                            applyClasses(container, classTheme.inlineContainer);
                        } else {
                            applyStyles(container, STYLES.inlineContainer(config.theme));
                        }
                        activeContainer = container;
                        renderQRContent(container, session, options);
                    } else {
                        // Modal mode (default)
                        activeModal = createModal({
                            title: options.title,
                            onCancel: function() {
                                stopPolling();
                                if (options.onCancel) options.onCancel();
                                reject(new Error('User cancelled'));
                            }
                        });

                        activeContainer = activeModal.content;
                        document.body.appendChild(activeModal.overlay);
                        renderQRContent(activeModal.content, session, options);
                    }

                    // Start polling for result
                    startPolling(session.session_id, {
                        timeout: options.timeout || config.timeout,
                        onSuccess: function(result) {
                            if (options.onSuccess) options.onSuccess(result);
                            resolve(result);
                        },
                        onFailure: function(error) {
                            if (options.onFailure) options.onFailure(error);
                            reject(error);
                        },
                        onCancel: options.onCancel,
                        onRetry: function() {
                            closeModal();
                            self.verify(options);
                        },
                        successTitle: options.successTitle,
                        successMessage: options.successMessage,
                        errorTitle: options.errorTitle,
                        errorMessage: options.errorMessage
                    });

                }).catch(function(error) {
                    log('error', 'Failed to create session:', error);
                    if (options.onFailure) options.onFailure(error);
                    reject(error);
                });
            });
        },

        /**
         * Close any open modal.
         */
        close: function() {
            closeModal();
        },

        /**
         * Get the current active session.
         *
         * @returns {Object|null} Current session or null
         */
        getActiveSession: function() {
            return activeSession;
        },

        /**
         * Manually check session status.
         *
         * @param {string} sessionId - Session ID to check
         * @returns {Promise} Resolves with session status
         */
        getSessionStatus: function(sessionId) {
            if (!initialized) {
                return Promise.reject(new Error('WaltVerify: SDK not initialized'));
            }
            return getSessionStatus(sessionId);
        },

        /**
         * Generate a QR code for verification data.
         *
         * @param {string} data - Data to encode
         * @param {Object} [options] - QR options
         * @returns {string} SVG string
         */
        generateQR: function(data, options) {
            return QRCode.toSVG(data, options);
        },

        /**
         * Set or update the class-based theme.
         * Can be called after init() to change themes dynamically.
         *
         * @param {Object} theme - Class-based theme object
         */
        setTheme: function(theme) {
            if (theme) {
                config.classTheme = theme;
                log('info', 'Theme updated', { mode: 'class-based' });
            } else {
                config.classTheme = null;
                log('info', 'Theme updated', { mode: 'inline-styles' });
            }
        },

        /**
         * Get the current theme configuration.
         *
         * @returns {Object} Current theme (classTheme if set, otherwise legacy theme)
         */
        getTheme: function() {
            if (config.classTheme) {
                return getClassTheme();
            }
            return config.theme;
        },

        /**
         * Get the default class-based theme.
         * Use this as a base for creating custom themes.
         *
         * @returns {Object} Default class-based theme
         */
        getDefaultTheme: function() {
            return Object.assign({}, DEFAULT_CLASS_THEME);
        },

        /**
         * Get SDK version.
         *
         * @returns {string} Version string
         */
        version: '1.0.0'
    };

    return WaltVerify;
});
