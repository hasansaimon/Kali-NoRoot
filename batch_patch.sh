#!/bin/bash
# Batch patch all APKs in a directory
# Usage: bash batch_patch.sh /path/to/apk/dir

DIR="${1:-.}"
OUTPUT_DIR="$HOME/rootspoofer/batch_output"
mkdir -p "$OUTPUT_DIR"

echo "Batch patching APKs in: $DIR"
echo "Output: $OUTPUT_DIR"
echo ""

count=0
for apk in "$DIR"/*.apk; do
    [ -f "$apk" ] || continue
        count=$((count + 1))
            echo "[$count] Patching: $(basename "$apk")"
                bash "$HOME/rootspoofer/patch_apk.sh" --output "$OUTPUT_DIR" "$apk"
                    echo ""
                    done

                    echo "Done! Patched $count APKs"
                    echo "Output directory: $OUTPUT_DIR"