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
  Switch, // 토글 스위치를 위해 Switch를 import 합니다.
  Linking, // 갤러리 앱을 열기 위해 Linking을 import 합니다.
  Animated, // 애니메이션을 위해 Animated를 import 합니다.
  // PanResponder, // 슬라이드 제스처를 위해 PanResponder는 더 이상 사용하지 않습니다.
  // Dimensions, // 화면 크기를 얻기 위해 Dimensions는 더 이상 사용하지 않습니다.
} from 'react-native';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
} from 'react-native-vision-camera';
import { Svg, Path, Circle } from 'react-native-svg';

interface CameraViewProps {
  onMediaCaptured: (media: { path: string; type: 'photo' | 'video' }) => void;
}

function CameraView({ onMediaCaptured }: CameraViewProps): React.JSX.Element {
  const { hasPermission, requestPermission } = useCameraPermission();
  const [devicePosition, setDevicePosition] = useState<'front' | 'back'>('back');
  const device = useCameraDevice(devicePosition);
  const camera = useRef<Camera>(null);

  const [captureMode, setCaptureMode] = useState<'photo' | 'video'>('photo');
  const [isRecording, setIsRecording] = useState(false);
  const [latestPhoto, setLatestPhoto] = useState<string | null>(null);

  // --- 설정 관련 상태 추가 ---
  const [isSettingsVisible, setIsSettingsVisible] = useState(false);
  const [isNightModeEnabled, setIsNightModeEnabled] = useState(true);
  const [isDetectionModeEnabled, setIsDetectionModeEnabled] = useState(false);

  // --- 플래시 효과를 위한 상태 추가 ---
  const flashOpacity = useRef(new Animated.Value(0)).current; // 애니메이션 값

  // --- 변환 중 인디케이터를 위한 상태 추가 ---
  const [isConverting, setIsConverting] = useState(false);

  // PanResponder 관련 코드는 제거합니다.
  // const photoTextTranslateX = useRef(new Animated.Value(0)).current;
  // const photoTextOpacity = useRef(new Animated.Value(1)).current;
  // const videoTextTranslateX = useRef(new Animated.Value(0)).current;
  // const videoTextOpacity = useRef(new Animated.Value(0.5)).current;
  // const modeSwitchPanResponder = useRef(...).current;
  // useEffect(() => { ... }, [captureMode]); // 이 useEffect도 제거합니다.

  useEffect(() => {
    if (!hasPermission) {
      requestPermission();
    }
    requestAndroidPermissions();
    fetchLatestPhoto();
  }, [hasPermission, requestPermission]);

  const requestAndroidPermissions = async () => {
    if (Platform.OS === 'android') {
      const statuses = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES,
        PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      ]);
      
      const readMediaImagesStatus = statuses[PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES];
      const recordAudioStatus = statuses[PermissionsAndroid.PERMISSIONS.RECORD_AUDIO];

      if (recordAudioStatus !== 'granted') {
        console.log('마이크 권한이 거부되었습니다. 영상 녹화 시 소리가 녹음되지 않습니다.');
      }
      
      if (readMediaImagesStatus === 'granted') {
        fetchLatestPhoto();
      } else {
        console.log('갤러리 접근 권한이 거부되었습니다.');
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
      } else {
        setLatestPhoto(null);
      }
    } catch (error) {
      console.error('최근 사진을 불러오는데 실패했습니다.', error);
    }
  };

  const handleGalleryPress = async () => {
    try {
      if (Platform.OS === 'ios') {
        await Linking.openURL('photos-redirect://');
      } else if (Platform.OS === 'android') {
        await Linking.openURL('content://media/external/images/media');
      }
    } catch (error) {
      console.error('갤러리를 여는 데 실패했습니다:', error);
      Alert.alert('오류', '갤러리 앱을 열 수 없습니다. 권한을 확인하거나 수동으로 열어주세요.');
    }
  };

  const onTakePhoto = async () => {
    if (camera.current == null) return;

    // 플래시 효과 시작
    Animated.timing(flashOpacity, {
      toValue: 0.8, // 불투명도를 0.8로 설정 (완전 흰색보다 부드럽게)
      duration: 100, // 빠르게 깜빡이도록 시간 설정
      useNativeDriver: true,
    }).start(() => {
      Animated.timing(flashOpacity, {
        toValue: 0,
        duration: 200, // 서서히 사라지도록 설정
        useNativeDriver: true,
      }).start();
    });

    try {
      const photo = await camera.current.takePhoto({
        flash: 'off',
        lowLightBoost: isNightModeEnabled,
      });
      const path = `file://${photo.path}`;
      
      await CameraRoll.save(path, { type: 'photo', album: 'NightLens' });
      
      fetchLatestPhoto();

      // 야간 모드가 활성화된 경우에만 변환 인디케이터 표시
      if (isNightModeEnabled) {
        setIsConverting(true);
        setTimeout(() => {
          setIsConverting(false);
        }, 5000); // 5초 동안 표시
      }

    } catch (e) {
      console.error('사진 촬영 또는 저장 실패: ', e);
      Alert.alert('오류', '사진을 처리하는 중 오류가 발생했습니다.');
    }
  };

  const onStartRecording = async () => {
    if (camera.current == null) return;
    setIsRecording(true);
    camera.current.startRecording({
      onRecordingFinished: async (video) => {
        setIsRecording(false);
        const path = `file://${video.path}`;
        
        try {
          await CameraRoll.save(path, { type: 'video', album: 'NightLens' });
          
          fetchLatestPhoto(); // 영상 촬영 후 최근 사진 업데이트 (썸네일용)

          // 야간 모드가 활성화된 경우에만 변환 인디케이터 표시
          if (isNightModeEnabled) {
            setIsConverting(true);
            setTimeout(() => {
              setIsConverting(false);
            }, 5000); // 5초 동안 표시
          }

        } catch (error) {
          console.error('영상 저장 실패: ', error);
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
  const toggleCaptureMode = () => setCaptureMode(m => (m === 'photo' ? 'video' : 'photo')); // 다시 활성화
  const toggleSettings = () => setIsSettingsVisible(prev => !prev);

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

      {/* 사진 촬영 시 깜빡이는 효과를 위한 오버레이 */}
      <Animated.View
        style={[styles.flashOverlay, { opacity: flashOpacity }]}
        pointerEvents="none" // 이 줄을 추가하여 터치 이벤트를 통과시킵니다.
      />

      {/* 변환 중 인디케이터 */}
      {isConverting && (
        <View style={styles.convertingIndicator}>
          <Text style={styles.convertingText}>변환 중...</Text>
        </View>
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

      {/* 설정 모달 */}
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

      {/* 하단 버튼 (갤러리, 촬영, 전환) */}
      <View style={styles.bottomButtonsContainer}>
        <TouchableOpacity style={styles.galleryButton} onPress={handleGalleryPress}>
          {latestPhoto ? (
            <Image source={{ uri: latestPhoto }} style={styles.galleryImage} />
          ) : (
            <Text style={styles.galleryPlaceholderText}>No Photo</Text>
          )}
        </TouchableOpacity>

        <View style={styles.captureCluster}>
          <TouchableOpacity
            style={[
              styles.captureButton,
              isNightModeEnabled && styles.nightModeCaptureButton,
            ]}
            onPress={captureMode === 'photo' ? onTakePhoto : (isRecording ? onStopRecording : onStartRecording)}
          >
            {isRecording ? <View style={styles.stopIcon} /> : <View style={styles.photoIcon} />}
          </TouchableOpacity>
          {/* 모드 전환 버튼을 다시 TouchableOpacity로 변경 */}
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
  container: { flex: 1 },
  text: { color: 'white', fontSize: 18 },
  topButtonsContainer: {
    position: 'absolute',
    top: 60,
    left: 20,
    right: 20,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  bottomButtonsContainer: {
    position: 'absolute',
    bottom: 40,
    width: '100%',
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'center',
  },
  iconButton: {
    padding: 10,
    backgroundColor: 'rgba(0,0,0,0.3)',
    borderRadius: 30,
  },
  galleryButton: {
    width: 50,
    height: 50,
    borderRadius: 10,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderWidth: 1,
    borderColor: 'white',
    justifyContent: 'center',
    alignItems: 'center',
    overflow: 'hidden',
  },
  galleryImage: {
    width: '100%',
    height: '100%',
  },
  galleryPlaceholderText: {
    color: 'white',
    fontSize: 10,
    textAlign: 'center',
  },
  captureCluster: {
    alignItems: 'center',
  },
  captureButton: {
    width: 70,
    height: 70,
    borderRadius: 35,
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 4,
    borderColor: 'rgba(0,0,0,0.2)',
  },
  nightModeCaptureButton: {
    borderColor: '#00FF00',
    borderWidth: 5,
    shadowColor: '#00FF00',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 1,
    shadowRadius: 10,
    elevation: 10,
  },
  photoIcon: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: 'white',
  },
  stopIcon: {
    width: 30,
    height: 30,
    borderRadius: 6,
    backgroundColor: 'red',
  },
  // 모드 전환 버튼 스타일을 다시 정의합니다.
  modeSwitchButton: {
    marginTop: 10,
    backgroundColor: 'rgba(0,0,0,0.5)',
    paddingVertical: 5,
    paddingHorizontal: 15,
    borderRadius: 20,
  },
  buttonText: {
    color: 'white',
    fontSize: 14,
    fontWeight: 'bold',
  },
  settingsContainer: {
    position: 'absolute',
    top: 120,
    alignSelf: 'center',
    width: '85%',
    backgroundColor: 'white',
    borderRadius: 20,
    padding: 20,
    elevation: 5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.25,
    shadowRadius: 3.84,
  },
  settingsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
  },
  settingsText: {
    fontSize: 16,
    color: 'black',
  },
  flashOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'white',
    zIndex: 10,
  },
  // 변환 중 인디케이터 스타일 추가
  convertingIndicator: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: [{ translateX: -75 }, { translateY: -25 }], // 중앙 정렬
    backgroundColor: 'rgba(0,0,0,0.7)',
    paddingVertical: 15,
    paddingHorizontal: 30,
    borderRadius: 10,
    zIndex: 20, // 다른 UI 위에 오도록 zIndex 설정
  },
  convertingText: {
    color: 'white',
    fontSize: 18,
    fontWeight: 'bold',
  },
});

export default CameraView;
