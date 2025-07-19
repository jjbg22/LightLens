// src/screens/SplashScreen.tsx

import React from 'react';
import { View, Text, StyleSheet, Image } from 'react-native';

const SplashScreen = () => {
  return (
    <View style={styles.splashContainer}>
    <Image
        // 3. 이미지 파일의 상대 경로를 정확하게 지정합니다.
        source={require('../static/images/clearshot_logo.png')}
        style={styles.logo}
      />


    </View>
  );
};

const styles = StyleSheet.create({
  splashContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#2c3e50',
  },
  splashTitle: {
    fontSize: 40,
    fontWeight: 'bold',
    color: '#FFFFFF',
    marginBottom: 20,
  },
  logo: {
    width: 300,
    height: 300,
  },
});

export default SplashScreen;