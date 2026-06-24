#!/bin/bash
# RootSpoofer - Complete Setup for Replit
# Run: bash setup.sh

set -e

echo "=========================================="
echo "  RootSpoofer - Complete Setup"
echo "  Ready to patch APKs for fake root"
echo "=========================================="

# Install dependencies
echo "[1/5] Installing dependencies..."
pkg update -y 2>/dev/null || apt-get update -y
pkg install -y openjdk-17 wget curl zip unzip 2>/dev/null || \
apt-get install -y openjdk-17-jdk wget curl zip unzip 2>/dev/null || \
echo "  Using system packages"

# Create directory structure
echo "[2/5] Creating directory structure..."
mkdir -p $HOME/rootspoofer/{gadgets,rootfs/{system/{bin,xbin,app,etc/init.d},sbin,data/{adb/magisk,local/tmp},selinux},output}

# Download apktool
echo "[3/5] Downloading apktool..."
wget -q https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar -O $HOME/rootspoofer/apktool.jar

# Download uber-apk-signer
echo "[4/5] Downloading APK signer..."
wget -q https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar -O $HOME/rootspoofer/uber-apk-signer.jar

# Create launcher scripts
echo "[5/5] Creating launcher scripts..."

# apktool wrapper
cat > $HOME/rootspoofer/bin/apktool << 'EOF'
#!/bin/bash
java -jar $HOME/rootspoofer/apktool.jar "$@"
EOF
chmod +x $HOME/rootspoofer/bin/apktool

# signer wrapper
cat > $HOME/rootspoofer/bin/apk-signer << 'EOF'
#!/bin/bash
java -jar $HOME/rootspoofer/uber-apk-signer.jar "$@"
EOF
chmod +x $HOME/rootspoofer/bin/apk-signer

# Add to PATH
export PATH="$HOME/rootspoofer/bin:$PATH"
echo 'export PATH="$HOME/rootspoofer/bin:$PATH"' >> $HOME/.bashrc

echo ""
echo "=========================================="
echo "  Setup Complete!"
echo "=========================================="
echo ""
echo "Next step: Copy all files into $HOME/rootspoofer/"
echo "Then run: bash $HOME/rootspoofer/patch_apk.sh target.apk"