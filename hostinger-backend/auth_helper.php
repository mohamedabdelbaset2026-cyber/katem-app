<?php
/**
 * Firebase ID Token (RS256) verifier & Firestore conversation membership validator
 * for Hostinger Shared Hosting (PHP 8.1+ with openssl & curl extensions).
 */

function katem_load_config(): array {
    $externalPath = dirname(__DIR__, 2) . '/katem_secure/config.php';
    if (is_readable($externalPath)) {
        return require $externalPath;
    }
    $localPath = __DIR__ . '/config.php';
    if (is_readable($localPath)) {
        return require $localPath;
    }
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => 'Server configuration missing (config.php).']);
    exit;
}

function katem_json_error(int $status, string $message): never {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => $message], JSON_UNESCAPED_UNICODE);
    exit;
}

function katem_base64url_decode(string $data): string {
    $pad = strlen($data) % 4;
    if ($pad > 0) {
        $data .= str_repeat('=', 4 - $pad);
    }
    return base64_decode(strtr($data, '-_', '+/')) ?: '';
}

function katem_base64url_encode(string $data): string {
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

/**
 * Extracts and verifies the Firebase Auth ID Token from `Authorization: Bearer <token>`.
 * Returns the verified Firebase UID string on success.
 */
function katem_verify_firebase_id_token(array $config): string {
    $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+(\S+)$/i', $authHeader, $matches)) {
        katem_json_error(401, 'Missing Authorization Bearer token.');
    }
    $jwt = $matches[1];
    $parts = explode('.', $jwt);
    if (count($parts) !== 3) {
        katem_json_error(401, 'Malformed JWT token.');
    }

    [$headerB64, $payloadB64, $sigB64] = $parts;
    $header = json_decode(katem_base64url_decode($headerB64), true);
    $payload = json_decode(katem_base64url_decode($payloadB64), true);
    $signature = katem_base64url_decode($sigB64);

    if (!is_array($header) || !is_array($payload) || ($header['alg'] ?? '') !== 'RS256' || empty($header['kid'])) {
        katem_json_error(401, 'Invalid JWT header.');
    }

    $projectId = $config['firebase_project_id'];
    $now = time();
    if (($payload['aud'] ?? '') !== $projectId) {
        katem_json_error(401, 'Token audience mismatch.');
    }
    if (($payload['iss'] ?? '') !== "https://securetoken.google.com/{$projectId}") {
        katem_json_error(401, 'Token issuer mismatch.');
    }
    if (($payload['exp'] ?? 0) <= $now || ($payload['iat'] ?? 0) > $now + 300) {
        katem_json_error(401, 'Token expired or not yet valid.');
    }
    $uid = (string)($payload['sub'] ?? '');
    if ($uid === '' || strlen($uid) > 128) {
        katem_json_error(401, 'Invalid token subject.');
    }

    // Fetch & cache Google public X.509 certificates for Firebase Auth
    $cacheFile = sys_get_temp_dir() . '/katem_firebase_certs.json';
    $certs = null;
    if (is_readable($cacheFile) && (time() - filemtime($cacheFile)) < 3600) {
        $certs = json_decode((string)file_get_contents($cacheFile), true);
    }
    if (!is_array($certs) || !isset($certs[$header['kid']])) {
        $ch = curl_init('https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com');
        curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 10]);
        $rawCerts = curl_exec($ch);
        curl_close($ch);
        $certs = json_decode((string)$rawCerts, true);
        if (is_array($certs)) {
            @file_put_contents($cacheFile, (string)$rawCerts, LOCK_EX);
        }
    }

    $certPem = $certs[$header['kid']] ?? null;
    if (!$certPem) {
        katem_json_error(401, 'Signing certificate not found for kid.');
    }

    $pubKey = openssl_pkey_get_public($certPem);
    $verified = $pubKey && openssl_verify("{$headerB64}.{$payloadB64}", $signature, $pubKey, OPENSSL_ALGO_SHA256) === 1;
    if (!$verified) {
        katem_json_error(401, 'Invalid Firebase ID Token signature.');
    }

    return $uid;
}

/**
 * Obtains a short-lived Google OAuth2 access token from the server-side Service Account JSON
 * and verifies that `$uid` is listed in Firestore `conversations/{conversationId}.memberUids`.
 */
function katem_assert_conversation_member(array $config, string $uid, string $conversationId): void {
    if (!preg_match('/^[a-zA-Z0-9_\-]{3,128}$/', $conversationId)) {
        katem_json_error(400, 'Invalid conversationId format.');
    }

    $saPath = $config['service_account_json'] ?? '';
    if (!is_readable($saPath)) {
        katem_json_error(500, 'Firebase Service Account JSON missing on Hostinger server.');
    }
    $sa = json_decode((string)file_get_contents($saPath), true);
    $now = time();
    $jwtHeader = katem_base64url_encode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $jwtClaim = katem_base64url_encode(json_encode([
        'iss' => $sa['client_email'],
        'scope' => 'https://www.googleapis.com/auth/datastore',
        'aud' => 'https://oauth2.googleapis.com/token',
        'iat' => $now,
        'exp' => $now + 300,
    ]));
    $sig = '';
    openssl_sign("{$jwtHeader}.{$jwtClaim}", $sig, $sa['private_key'], OPENSSL_ALGO_SHA256);
    $assertion = "{$jwtHeader}.{$jwtClaim}." . katem_base64url_encode($sig);

    $ch = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POSTFIELDS => http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion' => $assertion,
        ]),
        CURLOPT_TIMEOUT => 10,
    ]);
    $tokenRes = json_decode((string)curl_exec($ch), true);
    curl_close($ch);
    $accessToken = $tokenRes['access_token'] ?? '';
    if ($accessToken === '') {
        katem_json_error(500, 'Failed to obtain Firestore verification token.');
    }

    $projectId = $config['firebase_project_id'];
    $dbId = $config['firestore_database_id'];
    $docUrl = "https://firestore.googleapis.com/v1/projects/{$projectId}/databases/{$dbId}/documents/conversations/" . rawurlencode($conversationId);

    $ch2 = curl_init($docUrl);
    curl_setopt_array($ch2, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => ["Authorization: Bearer {$accessToken}"],
        CURLOPT_TIMEOUT => 10,
    ]);
    $docRaw = curl_exec($ch2);
    $httpCode = (int)curl_getinfo($ch2, CURLINFO_HTTP_CODE);
    curl_close($ch2);

    if ($httpCode !== 200) {
        katem_json_error(403, 'Conversation not found or unauthorized.');
    }

    $doc = json_decode((string)$docRaw, true);
    $values = $doc['fields']['memberUids']['arrayValue']['values'] ?? [];
    $members = array_map(static fn($v) => $v['stringValue'] ?? '', $values);
    if (!in_array($uid, $members, true)) {
        katem_json_error(403, 'User is not a member of this conversation.');
    }
}

/**
 * Simple file-backed per-UID sliding-window rate limiter on Hostinger Shared Hosting.
 */
function katem_enforce_rate_limit(array $config, string $uid): void {
    $limit = (int)($config['rate_limit_per_minute'] ?? 20);
    $file = sys_get_temp_dir() . '/katem_rl_' . sha1($uid) . '.json';
    $now = time();
    $entries = is_readable($file) ? (json_decode((string)file_get_contents($file), true) ?: []) : [];
    $entries = array_values(array_filter($entries, static fn($t) => ($now - (int)$t) < 60));
    if (count($entries) >= $limit) {
        katem_json_error(429, 'Rate limit exceeded. Please wait before uploading again.');
    }
    $entries[] = $now;
    @file_put_contents($file, json_encode($entries), LOCK_EX);
}
