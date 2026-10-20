-- 기존 회원은 사진 없이 유지한다. 실제 파일은 설정된 이미지 저장소에 보관한다.
ALTER TABLE users ADD COLUMN profile_image_key VARCHAR(200) NULL;
