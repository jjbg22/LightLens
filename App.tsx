import React, { useState, useEffect } from 'react'; // useCallback은 더 이상 필요 없으므로 제거
import { NativeModules } from 'react-native';
import { StyleSheet, View, TouchableOpacity, Image, Text } from 'react-native';
import CameraView from './screens/CameraView'; // 업데이트된 CameraView 컴포넌트
import SplashScreen from './screens/SplashScreen'; // SplashScreen 컴포넌트 임포트

const { IATModelModule } = NativeModules;

// 미디어 타입을 위한 인터페이스
interface Media {
  path: string;
  type: 'photo' | 'video';
}

function App(): React.JSX.Element {
  const [capturedMedia, setCapturedMedia] = useState<Media | null>(null);
  const [showSplash, setShowSplash] = useState(true); // 스플래시 화면 상태 추가

  // 스플래시 화면 표시를 위한 타이머 로직 (App 컴포넌트에서 관리)
  useEffect(() => {
    console.log('IATModelModule 객체:', IATModelModule);

    const timer = setTimeout(() => {
      setShowSplash(false);
    }, 3000); // 3초 후에 스플래시 화면을 숨깁니다.

    // 모델 초기화 호출
    NativeModules.IATModelModule.initializeModel()
    .then((result: any) => console.log('Model initialized:', result))
    .catch((error: any) => console.error('Failed to initialize model:', error));

    // 컴포넌트 언마운트 시 타이머를 클리어합니다.
    return () => clearTimeout(timer);
  }, []); // 빈 배열은 컴포넌트 마운트 시 한 번만 실행됨을 의미합니다.

  // CameraView로부터 미디어 객체를 받아 상태를 업데이트
  const handleMediaCaptured = (media: Media) => {
    console.log(`미디어 캡처: ${media.type} at ${media.path}`);
    setCapturedMedia(media);
  };

  // 다시 찍기 함수
  const handleRetake = () => {
    setCapturedMedia(null);
  };

  // showSplash 상태에 따라 스플래시 화면 또는 메인 콘텐츠를 렌더링합니다.
  if (showSplash) {
    return <SplashScreen />; // onFinish prop 없이 SplashScreen 렌더링
  }

  // 스플래시 화면이 끝나면 기존 앱 내용을 렌더링합니다.
  return (
    <View style={styles.container}>
      {capturedMedia ? (
        // 캡처된 미디어가 있으면 미리보기 화면을 렌더링
        <View style={styles.container}>
          {capturedMedia.type === 'photo' ? (
            <Image source={{ uri: capturedMedia.path }} style={StyleSheet.absoluteFill} />
          ) : (
            // 비디오의 경우, 현재는 텍스트로만 표시합니다.
            // 실제 재생을 위해서는 react-native-video 라이브러리가 필요합니다.
            <View style={styles.videoPreview}>
              <Text style={styles.previewText}>비디오가 촬영되었습니다!</Text>
              <Text style={styles.pathText}>{capturedMedia.path}</Text>
            </View>
          )}
          <TouchableOpacity style={styles.retakeButton} onPress={handleRetake}>
            <Text style={styles.buttonText}>다시 찍기</Text>
          </TouchableOpacity>
        </View>
      ) : (
        // 캡처된 미디어가 없으면 카메라 뷰를 렌더링
        <CameraView onMediaCaptured={handleMediaCaptured} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
  videoPreview: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  previewText: {
    color: 'white',
    fontSize: 24,
    fontWeight: 'bold',
  },
  pathText: {
    color: 'gray',
    fontSize: 12,
    marginTop: 10,
  },
  retakeButton: {
    position: 'absolute',
    bottom: 50,
    alignSelf: 'center',
    backgroundColor: 'rgba(0,0,0,0.5)',
    paddingVertical: 15,
    paddingHorizontal: 30,
    borderRadius: 30,
  },
  buttonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: 'bold',
  },
});

export default App;
