import React, { useState, useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import CameraView from './screens/CameraView';
import SplashScreen from './screens/SplashScreen';

function App(): React.JSX.Element {
  const [showSplash, setShowSplash] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => {
      setShowSplash(false);
    }, 3000);
    return () => clearTimeout(timer);
  }, []);

  if (showSplash) {
    return <SplashScreen />;
  }

  // CameraView만 렌더링하면 됩니다.
  return (
    <View style={styles.container}>
      <CameraView />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
});

export default App;
