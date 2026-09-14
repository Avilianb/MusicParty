# Music Party Local Build Script
# Requirements: Node.js, Java 21, Go, Wails CLI

echo "--- 1. Building Frontend ---"
cd music-party-web
npm install
npm run build
cd ..

echo "--- 2. Building Java Backend ---"
# Copy frontend dist to static
if (Test-Path "src/main/resources/static") { rm -r src/main/resources/static }
mkdir -p src/main/resources/static
cp -r music-party-web/dist/* src/main/resources/static/
mvn clean package -DskipTests

echo "--- 3. Preparing Launcher Assets ---"
if (-Not (Test-Path "launcher/bin")) { mkdir launcher/bin }
cp target/music-party-*.jar launcher/bin/server.jar

echo "--- 4. Building Wails Launcher ---"
cd launcher
wails build -platform windows/amd64
cd ..

echo "DONE! Your EXE is at: launcher/build/bin/MusicParty.exe"
