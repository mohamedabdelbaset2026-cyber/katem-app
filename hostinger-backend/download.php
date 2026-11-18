<?php
require_once __DIR__ . '/auth_helper.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    katem_json_error(405, 'Method Not Allowed.');
}

$config = katem_load_config();
$uid = katem_verify_firebase_id_token($config);

$conversationId = trim((string)($_GET['cid'] ?? ''));
$fileId = trim((string)($_GET['fid'] ?? ''));

katem_assert_conversation_member($config, $uid, $conversationId);

// Prevent path traversal strictly via allowlisted filename regex
if (!preg_match('/^med_[a-f0-9]{24}\.[a-z0-9]{2,5}$/', $fileId)) {
    katem_json_error(400, 'Invalid file identifier.');
}

$filePath = rtrim($config['private_storage_dir'], '/') . "/{$conversationId}/{$fileId}";
if (!is_file($filePath) || !is_readable($filePath)) {
    katem_json_error(404, 'Media file not found.');
}

$finfo = new finfo(FILEINFO_MIME_TYPE);
$mimeType = $finfo->file($filePath) ?: 'application/octet-stream';

header('Content-Type: ' . $mimeType);
header('Content-Length: ' . filesize($filePath));
header('X-Content-Type-Options: nosniff');
header('Cache-Control: private, max-age=86400');
header('Content-Disposition: inline; filename="' . $fileId . '"');

readfile($filePath);
exit;
