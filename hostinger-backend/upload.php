<?php
require_once __DIR__ . '/auth_helper.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    katem_json_error(405, 'Method Not Allowed.');
}

$config = katem_load_config();
$uid = katem_verify_firebase_id_token($config);
katem_enforce_rate_limit($config, $uid);

$conversationId = trim((string)($_POST['conversationId'] ?? ''));
katem_assert_conversation_member($config, $uid, $conversationId);

if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
    katem_json_error(400, 'No valid uploaded file received.');
}

$tmpPath = $_FILES['file']['tmp_name'];
$fileSize = (int)$_FILES['file']['size'];
$originalName = basename((string)($_FILES['file']['name'] ?? 'file'));

if ($fileSize <= 0 || $fileSize > (int)$config['max_file_size_bytes']) {
    katem_json_error(413, 'File size exceeds allowed limit.');
}

// Detect actual MIME type server-side using finfo (never trust client extension alone)
$finfo = new finfo(FILEINFO_MIME_TYPE);
$detectedMime = $finfo->file($tmpPath) ?: 'application/octet-stream';
$allowedMap = $config['allowed_mime_map'];
if (!isset($allowedMap[$detectedMime])) {
    katem_json_error(415, "Unsupported media type: {$detectedMime}");
}

$ext = $allowedMap[$detectedMime];
$mediaId = 'med_' . bin2hex(random_bytes(12));
$storageRoot = rtrim($config['private_storage_dir'], '/');
$convDir = "{$storageRoot}/{$conversationId}";

if (!is_dir($convDir) && !mkdir($convDir, 0750, true)) {
    katem_json_error(500, 'Failed to initialize private storage folder.');
}

$storedFileName = "{$mediaId}.{$ext}";
$destination = "{$convDir}/{$storedFileName}";
if (!move_uploaded_file($tmpPath, $destination)) {
    katem_json_error(500, 'Failed to save uploaded media securely.');
}
@chmod($destination, 0640);

$safeOriginal = preg_replace('/[^a-zA-Z0-9._\-\p{Arabic} ]/u', '_', $originalName) ?: $storedFileName;
$downloadUrl = rtrim($config['public_api_base_url'], '/')
    . '/download.php?cid=' . rawurlencode($conversationId)
    . '&fid=' . rawurlencode($storedFileName);

header('Content-Type: application/json; charset=utf-8');
echo json_encode([
    'mediaId'     => $mediaId,
    'remotePath'  => "katem_private_storage/{$conversationId}/{$storedFileName}",
    'downloadUrl' => $downloadUrl,
    'mimeType'    => $detectedMime,
    'fileName'    => mb_substr($safeOriginal, 0, 180),
    'sizeBytes'   => $fileSize,
], JSON_UNESCAPED_UNICODE);
