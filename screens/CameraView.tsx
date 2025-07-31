import React, { useState, useRef, useEffect } from 'react';
import {
  StyleSheet,
  View,
  Text,
  TouchableOpacity,
  Image,
  PermissionsAndroid,
  Platform,
  Alert,
  Switch,
  Linking,
  Animated,
  ActivityIndicator,
  LayoutChangeEvent, // onLayout 이벤트 타입을 위해 추가
} from 'react-native';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
} from 'react-native-vision-camera';
import { Svg, Path, Circle } from 'react-native-svg';
import { NativeModules } from 'react-native';
import RNFS from 'react-native-fs';

const { IATModelModule } = NativeModules;

// 이제 이 컴포넌트는 독립적으로 동작하므로 props가 필요 없습니다.
function CameraView(): React.JSX.Element {
  const { hasPermission, requestPermission } = useCameraPermission();
  const [devicePosition, setDevicePosition] = useState<'front' | 'back'>('back');
  const device = useCameraDevice(devicePosition);
  const camera = useRef<Camera>(null);

  const [captureMode, setCaptureMode] = useState<'photo' | 'video'>('photo');
  const [isRecording, setIsRecording] = useState(false);
  const [latestPhoto, setLatestPhoto] = useState<string | null>(null);

  const [isSettingsVisible, setIsSettingsVisible] = useState(false);
  const [isNightModeEnabled, setIsNightModeEnabled] = useState(true);
  const [isDetectionModeEnabled, setIsDetectionModeEnabled] = useState(false);

  const flashOpacity = useRef(new Animated.Value(0)).current;
  const [isLoading, setIsLoading] = useState(false);

  // --- ❗️ 애니메이션을 위한 상태 추가 ---
  const [animatingImage, setAnimatingImage] = useState<{ uri: string } | null>(null);
  const animatedValue = useRef(new Animated.Value(0)).current;
  const [galleryIconLayout, setGalleryIconLayout] = useState({ x: 0, y: 0, width: 0, height: 0 });

  useEffect(() => {
    if (!hasPermission) {
      requestPermission();
    }
    requestAndroidPermissions();

    IATModelModule.initializeModel()
      .then((result: string) => console.log('Model Initialization:', result))
      .catch((error: Error) => Alert.alert('모델 초기화 실패', error.message));
  }, [hasPermission]);

  const requestAndroidPermissions = async () => {
    if (Platform.OS === 'android') {
      const statuses = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES,
        PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      ]);
      if (statuses[PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES] === 'granted') {
        fetchLatestPhoto();
      }
    } else {
      fetchLatestPhoto();
    }
  };

  const fetchLatestPhoto = async () => {
    try {
      const { edges } = await CameraRoll.getPhotos({ first: 1, assetType: 'Photos' });
      if (edges.length > 0) {
        setLatestPhoto(edges[0].node.image.uri);
      }
    } catch (error) {
      console.error('최근 사진을 불러오는데 실패했습니다.', error);
    }
  };

  const handleGalleryPress = async () => {
    try {
      if (Platform.OS === 'ios') Linking.openURL('photos-redirect://');
      else Linking.openURL('content://media/external/images/media');
    } catch (error) {
      Alert.alert('오류', '갤러리 앱을 열 수 없습니다.');
    }
  };

  const saveAndAnimate = async (imageUri: string, isBase64: boolean) => {
    let finalUri = imageUri;
    try {
      if (isBase64) {
        // Base64 데이터인 경우, 파일로 저장 후 갤러리에 추가
        const tempPath = `${RNFS.CachesDirectoryPath}/processed_${new Date().getTime()}.png`;
        await RNFS.writeFile(tempPath, imageUri.split(',')[1], 'base64');
        finalUri = `file://${tempPath}`;
      }
      await CameraRoll.save(finalUri, { type: 'photo', album: 'NightLens' });
      await fetchLatestPhoto(); // 갤러리 아이콘 업데이트

      // 애니메이션 시작
      setAnimatingImage({ uri: finalUri });
      animatedValue.setValue(0); // 애니메이션 값 초기화

      Animated.timing(animatedValue, {
        toValue: 1,
        duration: 800, // 애니메이션 지속 시간
        useNativeDriver: true,
      }).start(() => {
        setAnimatingImage(null); // 애니메이션 종료 후 이미지 숨김
      });

    } catch (e: any) {
      Alert.alert('저장 오류', '처리된 사진을 저장하는 데 실패했습니다.');
      console.error('Save failed:', e);
    }
  };

  const onTakePhoto = async () => {
    if (camera.current == null || isLoading) return;

    Animated.sequence([
      Animated.timing(flashOpacity, { toValue: 0.8, duration: 100, useNativeDriver: true }),
      Animated.timing(flashOpacity, { toValue: 0, duration: 200, useNativeDriver: true }),
    ]).start();

    try {
      const photo = await camera.current.takePhoto({ flash: 'off' });
      const path = `file://${photo.path}`;

      if (isNightModeEnabled) {
        setIsLoading(true);
        const imageBase64 = await RNFS.readFile(photo.path, 'base64');
        const resultBase64 = await IATModelModule.runModelOnImage(imageBase64);
        const finalUri = `data:image/png;base64,${resultBase64}`;
        await saveAndAnimate(finalUri, true);
      } else {
        await saveAndAnimate(path, false);
      }
    } catch (e: any) {
      console.error('사진 처리 실패: ', e);
      Alert.alert('오류', '사진을 처리하는 중 오류가 발생했습니다.');
    } finally {
      setIsLoading(false);
    }
  };
  
  const onStartRecording = () => { 
    if (camera.current == null) return;
    setIsRecording(true);
    camera.current.startRecording({
      onRecordingFinished: async (video) => {
        setIsRecording(false);
        const path = `file://${video.path}`;
        try {
          await CameraRoll.save(path, { type: 'video', album: 'NightLens' });
          fetchLatestPhoto();
        } catch (error) {
          Alert.alert('오류', '영상을 저장하는 중 오류가 발생했습니다.');
        }
      },
      onRecordingError: (error) => {
        console.error('녹화 오류: ', error);
        setIsRecording(false);
      },
    });
  };
  const onStopRecording = async () => { 
    if (camera.current == null) return;
    try {
      await camera.current.stopRecording();
    } catch (e) {
      console.error('녹화 중지 실패: ', e);
    }
  };
  const onFlipCamera = () => setDevicePosition(p => (p === 'back' ? 'front' : 'back'));
  const toggleCaptureMode = () => setCaptureMode(m => (m === 'photo' ? 'video' : 'photo'));
  const toggleSettings = () => setIsSettingsVisible(prev => !prev);

  // 갤러리 아이콘의 위치를 저장하는 함수
  const onGalleryLayout = (event: LayoutChangeEvent) => {
    // onLayout 이벤트는 렌더링 시점에 여러 번 발생할 수 있으므로,
    // galleryIconLayout의 위치를 한 번만 측정하도록 조건을 추가할 수 있습니다.
    // 여기서는 단순화를 위해 매번 업데이트하도록 둡니다.
    const { x, y, width, height } = event.nativeEvent.layout;
    // 전체 화면 기준의 절대 좌표를 얻기 위해 `measure`를 사용하는 것이 더 정확할 수 있습니다.
    // 이 예제에서는 부모 View 내의 상대적 위치를 사용합니다.
    setGalleryIconLayout({ x, y, width, height });
  };

  // 애니메이션 스타일 계산
  const animatedImageStyle = {
    // 애니메이션 스타일은 화면 중앙에서 시작하여 갤러리 아이콘 위치로 이동하도록 계산됩니다.
    // `absoluteFill`을 사용하므로 초기 위치는 (0,0)이지만, scale: 1로 전체 화면을 채웁니다.
    // translateX, translateY는 화면 중앙을 기준으로 계산하는 것이 더 정확한 애니메이션을 만듭니다.
    // 여기서는 단순화된 계산을 사용합니다.
    transform: [
      {
        translateX: animatedValue.interpolate({
          inputRange: [0, 1],
          outputRange: [0, galleryIconLayout.x],
        }),
      },
      {
        translateY: animatedValue.interpolate({
          inputRange: [0, 1],
          outputRange: [0, galleryIconLayout.y],
        }),
      },
      {
        scale: animatedValue.interpolate({
          inputRange: [0, 0.5, 1],
          outputRange: [1, 1, 0.1], // 처음엔 크기 유지하다가 마지막에 작아짐
        }),
      },
    ],
    opacity: animatedValue.interpolate({
      inputRange: [0, 0.8, 1],
      outputRange: [1, 1, 0], // 마지막에 사라짐
    }),
  };

  if (device == null) return <View style={styles.container}><Text style={styles.text}>카메라를 찾을 수 없습니다.</Text></View>;

  return (
    <View style={styles.container}>
      <Camera
        ref={camera}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
        photo={captureMode === 'photo'}
        video={captureMode === 'video'}
        audio={captureMode === 'video'}
      />

      <Animated.View style={[styles.flashOverlay, { opacity: flashOpacity }]} pointerEvents="none" />

      {isLoading && (
        <View style={styles.loadingIndicator}>
          <ActivityIndicator size="large" color="white" />
          <Text style={styles.loadingText}>이미지 개선 중...</Text>
        </View>
      )}

      {/* ❗️ 애니메이션을 위한 이미지 뷰 */}
      {animatingImage && (
        <Animated.Image
          source={{ uri: animatingImage.uri }}
          style={[styles.animatedImage, animatedImageStyle]}
        />
      )}

      {/* 상단 버튼 (플러스, 설정) */}
      <View style={styles.topButtonsContainer}>
        <TouchableOpacity style={styles.iconButton}>
          <Svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <Path d="M12 5v14M5 12h14" />
          </Svg>
        </TouchableOpacity>
        <TouchableOpacity style={styles.iconButton} onPress={toggleSettings}>
          <Svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <Circle cx="12" cy="12" r="3" />
            <Path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06-.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </Svg>
        </TouchableOpacity>
      </View>
      {isSettingsVisible && (
        <View style={styles.settingsContainer}>
            <View style={styles.settingsRow}>
                <Text style={styles.settingsText}>Night Enhancement Mode</Text>
                <Switch
                    value={isNightModeEnabled}
                    onValueChange={setIsNightModeEnabled}
                    trackColor={{ false: '#767577', true: '#81b0ff' }}
                    thumbColor={isNightModeEnabled ? '#f5dd4b' : '#f4f3f4'}
                />
            </View>
            <View style={styles.settingsRow}>
                <Text style={styles.settingsText}>Detection Mode</Text>
                <Switch
                    value={isDetectionModeEnabled}
                    onValueChange={setIsDetectionModeEnabled}
                    trackColor={{ false: '#767577', true: '#81b0ff' }}
                    thumbColor={isDetectionModeEnabled ? '#f5dd4b' : '#f4f3f4'}
                />
            </View>
        </View>
      )}


      <View style={styles.bottomButtonsContainer}>
        {/* ❗️ onLayout 이벤트 핸들러 추가 */}
        <TouchableOpacity style={styles.galleryButton} onPress={handleGalleryPress} onLayout={onGalleryLayout}>
          {latestPhoto ? (
            <Image source={{ uri: latestPhoto }} style={styles.galleryImage} />
          ) : (
            <View style={styles.galleryPlaceholder} />
          )}
        </TouchableOpacity>

        <View style={styles.captureCluster}>
          <TouchableOpacity
            style={[ styles.captureButton, isNightModeEnabled && styles.nightModeCaptureButton ]}
            onPress={captureMode === 'photo' ? onTakePhoto : (isRecording ? onStopRecording : onStartRecording)}
          >
            {isRecording ? <View style={styles.stopIcon} /> : <View style={styles.photoIcon} />}
          </TouchableOpacity>
          <TouchableOpacity onPress={toggleCaptureMode} style={styles.modeSwitchButton}>
            <Text style={styles.buttonText}>{captureMode === 'photo' ? '사진' : '비디오'}</Text>
          </TouchableOpacity>
        </View>
        <TouchableOpacity style={styles.iconButton} onPress={onFlipCamera}>
            <Svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <Path d="M17 2l4 4-4 4" />
                <Path d="M3 11v-1a4 4 0 0 1 4-4h14" />
                <Path d="M7 22l-4-4 4-4" />
                <Path d="M21 13v1a4 4 0 0 1-4 4H3" />
            </Svg>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: 'black' },
  text: { color: 'white', fontSize: 18, textAlign: 'center' },
  topButtonsContainer: { position: 'absolute', top: 60, left: 20, right: 20, flexDirection: 'row', justifyContent: 'space-between' },
  bottomButtonsContainer: { position: 'absolute', bottom: 40, width: '100%', flexDirection: 'row', justifyContent: 'space-around', alignItems: 'center' },
  iconButton: { padding: 10, backgroundColor: 'rgba(0,0,0,0.3)', borderRadius: 30 },
  galleryButton: { width: 50, height: 50, borderRadius: 10, borderWidth: 1, borderColor: 'white', justifyContent: 'center', alignItems: 'center', overflow: 'hidden' },
  galleryImage: { width: '100%', height: '100%' },
  galleryPlaceholder: { width: '100%', height: '100%', backgroundColor: 'rgba(255,255,255,0.2)' },
  captureCluster: { alignItems: 'center' },
  captureButton: { width: 70, height: 70, borderRadius: 35, backgroundColor: 'rgba(255, 255, 255, 0.9)', justifyContent: 'center', alignItems: 'center', borderWidth: 4, borderColor: 'rgba(0,0,0,0.2)' },
  nightModeCaptureButton: { borderColor: '#81b0ff', borderWidth: 4, shadowColor: '#81b0ff', shadowOffset: { width: 0, height: 0 }, shadowOpacity: 0.9, shadowRadius: 8, elevation: 10 },
  photoIcon: { width: 60, height: 60, borderRadius: 30, backgroundColor: 'white' },
  stopIcon: { width: 30, height: 30, borderRadius: 6, backgroundColor: 'red' },
  modeSwitchButton: { marginTop: 10, backgroundColor: 'rgba(0,0,0,0.5)', paddingVertical: 5, paddingHorizontal: 15, borderRadius: 20 },
  buttonText: { color: 'white', fontSize: 14, fontWeight: 'bold' },
  settingsContainer: { position: 'absolute', top: 120, alignSelf: 'center', width: '85%', backgroundColor: 'rgba(0,0,0,0.7)', borderRadius: 20, padding: 20, elevation: 5 },
  settingsRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 10 },
  settingsText: { fontSize: 16, color: 'white' },
  flashOverlay: { ...StyleSheet.absoluteFillObject, backgroundColor: 'white', zIndex: 10 },
  loadingIndicator: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 20 },
  loadingText: { color: 'white', fontSize: 18, fontWeight: 'bold', marginTop: 10 },
  animatedImage: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    resizeMode: 'contain',
  },
});

export default CameraView;
