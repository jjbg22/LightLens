// import React, { useState } from 'react';
// import {
//   StyleSheet,
//   View,
//   Text,
//   TouchableOpacity,
//   Image,
//   Alert,
//   ActivityIndicator,
//   SafeAreaView,
// } from 'react-native';
// import { NativeModules } from 'react-native';
// import RNFS from 'react-native-fs';
// import { CameraRoll } from '@react-native-camera-roll/camera-roll';
// import { Svg, Path } from 'react-native-svg';

// const { IATModelModule } = NativeModules;

// interface GalleryEditorViewProps {
//   imageUri: string;
//   onClose: () => void;
// }

// function GalleryEditorView({ imageUri, onClose }: GalleryEditorViewProps): React.JSX.Element {
//   const [isLoading, setIsLoading] = useState(false);
//   const [processedImageUri, setProcessedImageUri] = useState<string | null>(null);

//   const handleConvert = async () => {
//     setIsLoading(true);
//     try {
//       // 1. 'file://' 접두사 제거 (RNFS는 순수 경로 필요)
//       const filePath = imageUri.startsWith('file://') ? imageUri.substring(7) : imageUri;
      
//       // 2. 이미지 파일을 Base64로 인코딩
//       const imageBase64 = await RNFS.readFile(filePath, 'base64');
      
//       // 3. AI 모델 실행
//       const resultBase64 = await IATModelModule.runModelOnImage(imageBase64);
//       const finalUri = `data:image/png;base64,${resultBase64}`;
      
//       // 4. 결과 자동 저장
//       const tempPath = `${RNFS.CachesDirectoryPath}/processed_gallery_${new Date().getTime()}.png`;
//       await RNFS.writeFile(tempPath, resultBase64, 'base64');
//       await CameraRoll.save(`file://${tempPath}`, { type: 'photo', album: 'NightLens' });

//       setProcessedImageUri(finalUri); // 화면에 처리된 이미지 표시
//       Alert.alert('변환 및 저장 완료', '개선된 사진이 갤러리에 저장되었습니다.');

//     } catch (error: any) {
//       console.error('갤러리 사진 처리 실패:', error);
//       Alert.alert('오류', '사진을 처리하는 중 문제가 발생했습니다.');
//     } finally {
//       setIsLoading(false);
//     }
//   };

//   return (
//     <SafeAreaView style={styles.container}>
//       {/* 상단 헤더 (뒤로가기 버튼) */}
//       <View style={styles.header}>
//         <TouchableOpacity onPress={onClose} style={styles.backButton}>
//           <Svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
//             <Path d="M15 18l-6-6 6-6" />
//           </Svg>
//         </TouchableOpacity>
//         <Text style={styles.headerTitle}>사진 편집</Text>
//         <View style={{ width: 40 }} />
//       </View>

//       {/* 이미지 표시 영역 */}
//       <View style={styles.imageContainer}>
//         <Image
//           source={{ uri: processedImageUri || imageUri }}
//           style={styles.image}
//           resizeMode="contain"
//         />
//       </View>

//       {/* 하단 변환 버튼 */}
//       <View style={styles.bottomContainer}>
//         {/* 처리된 이미지가 없을 때만 변환 버튼 표시 */}
//         {!processedImageUri && (
//           <TouchableOpacity style={styles.convertButton} onPress={handleConvert}>
//             <Text style={styles.buttonText}>개선하기</Text>
//           </TouchableOpacity>
//         )}
//       </View>
      
//       {/* 로딩 인디케이터 */}
//       {isLoading && (
//         <View style={styles.loadingIndicator}>
//           <ActivityIndicator size="large" color="white" />
//           <Text style={styles.loadingText}>이미지 개선 중...</Text>
//         </View>
//       )}
//     </SafeAreaView>
//   );
// }

// const styles = StyleSheet.create({
//   container: { flex: 1, backgroundColor: 'black' },
//   header: {
//     flexDirection: 'row',
//     alignItems: 'center',
//     justifyContent: 'space-between',
//     paddingHorizontal: 15,
//     paddingVertical: 10,
//     borderBottomWidth: 1,
//     borderBottomColor: '#333',
//   },
//   backButton: { padding: 5 },
//   headerTitle: { color: 'white', fontSize: 20, fontWeight: 'bold' },
//   imageContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 },
//   image: { width: '100%', height: '100%' },
//   bottomContainer: {
//     padding: 30,
//     alignItems: 'center',
//     borderTopWidth: 1,
//     borderTopColor: '#333',
//   },
//   convertButton: {
//     backgroundColor: '#5A67D8',
//     paddingVertical: 15,
//     paddingHorizontal: 50,
//     borderRadius: 30,
//   },
//   buttonText: { color: 'white', fontSize: 18, fontWeight: 'bold' },
//   loadingIndicator: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.7)', zIndex: 20 },
//   loadingText: { color: 'white', fontSize: 18, fontWeight: 'bold', marginTop: 10 },
// });

// export default GalleryEditorView;
