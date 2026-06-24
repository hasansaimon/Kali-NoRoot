#!/bin/bash
# RootSpoofer - Quick Start for Replit
# Just run: bash replit_quickstart.sh

echo "=========================================="
echo "  RootSpoofer - Replit Quick Start"
echo "=========================================="

# 1. Create structure
echo "[1] Creating directory structure..."
mkdir -p $HOME/rootspoofer/{gadgets,rootfs/{system/{bin,xbin,app,etc/init.d},sbin,data/{adb/magisk,local/tmp},selenium},output,bin}

# 2. Download dependencies
echo "[2] Downloading apktool + signer..."
wget -q https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar -O $HOME/rootspoofer/apktool.jar
wget -q https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar -O $HOME/rootspoofer/uber-apk-signer.jar

# 3. Create launchers
echo "[3] Creating launcher scripts..."
echo '#!/bin/bash
java -jar $HOME/rootspoofer/apktool.jar "$@"' > $HOME/rootspoofer/bin/apktool
chmod +x $HOME/rootspoofer/bin/apktool

echo '#!/bin/bash
java -jar $HOME/rootspoofer/uber-apk-signer.jar "$@"' > $HOME/rootspoofer/bin/apk-signer
chmod +x $HOME/rootspoofer/bin/apk-signer

# 4. Copy the patcher script
echo "[4] Verifying patcher script..."
if [ -f "patch_apk.sh" ]; then
    cp patch_apk.sh $HOME/rootspoofer/patch_apk.sh
        chmod +x $HOME/rootspoofer/patch_apk.sh
        fi

        # 5. Export path
        export PATH="$HOME/rootspoofer/bin:$PATH"
        echo 'export PATH="$HOME/rootspoofer/bin:$PATH"' >> $HOME/.bashrc

        echo ""
        echo "=========================================="
        echo "  Ready!"
        echo "=========================================="
        echo ""
        echo "To patch an APK:"
        echo "  bash patch_apk.sh /path/to/target.apk"
        echo ""
        echo "To download + patch from device:"
        echo "  bash patch_apk.sh --download com.example.app"
        echo ""