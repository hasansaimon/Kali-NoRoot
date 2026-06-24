#!/bin/bash
# Download Frida gadgets for all architectures
# Run: bash download_gadgets.sh

FRIDA_VERSION="16.2.1"
OUTPUT_DIR="$HOME/rootspoofer/gadgets"
mkdir -p "$OUTPUT_DIR"

echo "Downloading Frida gadgets v$FRIDA_VERSION..."

for arch in arm64 arm x86_64 x86; do
    echo "  → frida-gadget-$arch..."
        wget -q --show-progress \
                "https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/frida-gadget-${FRIDA_VERSION}-android-${arch}.so.xz" \
                        -O "$OUTPUT_DIR/frida-gadget-${arch}.so.xz"
                            xz -d "$OUTPUT_DIR/frida-gadget-${arch}.so.xz"
                                echo "    Done: $OUTPUT_DIR/frida-gadget-${arch}.so"
                                done

                                echo ""
                                echo "All gadgets downloaded to $OUTPUT_DIR"
                                ls -lh "$OUTPUT_DIR"