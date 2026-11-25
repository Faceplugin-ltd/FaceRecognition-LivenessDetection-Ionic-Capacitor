import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.faceplugin.facerecognition.app',
  appName: 'Faceplugin-Face-SDK',
  webDir: 'dist',
  // Opaque. A fully transparent WebView has nothing behind it on either platform, so
  // every screen that is not the camera renders black. The plugin turns transparency
  // on for the length of a live preview and back off afterwards.
  backgroundColor: '#1C1B1F',
  server: {
    androidScheme: 'https',
  },
  plugins: {
    Camera: {
      permissions: ['camera', 'photos'],
    },
  },
};

export default config;
