package com.scrtrans

/**
 * Fixed translations, applied on **exact match only**.
 *
 * ML Kit has no ja->ko model; it routes ja->en->ko, and beauty jargon does not
 * survive the detour. It also keeps turning noun phrases into "-하십시오" imperatives,
 * which is the same English round-trip showing through.
 *
 * The glossary skips the engine entirely, so a wrong entry is worse than ML Kit —
 * it is wrong with confidence. Hence the split below: [VERIFIED] was observed on a
 * real device, [GUESSED] was not. Entries that actually fire are tagged in
 * translations.jsonl so the guesses can be reviewed against real screens.
 *
 * Substring replacement is deliberately not done — it would splice terms into the
 * middle of longer sentences.
 */
object Glossary {

    /** Observed on the device: either a confirmed ML Kit mistranslation or a read UI label. */
    private val VERIFIED: Map<String, String> = mapOf(
        // --- confirmed ML Kit failures (left column was the wrong output) ---
        "セミロング" to "세미롱",                       // was: semilone
        "ミディアム" to "미디엄",                       // was: 중간
        "ショート" to "숏",                             // was: 짧은
        "ロング" to "롱",                               // was: 긴
        "ベリーショート" to "베리 숏",                  // was: 매우 짧은
        "レイヤーボブ" to "레이어드 보브",              // was: 층 밥
        "切りっぱなしボブ" to "블런트 보브",            // was: 밥을 자르십시오
        "ボブアレンジヘア" to "보브 스타일링 헤어",     // was: 밥 머리를 정렬하십시오
        "ミルクティーベージュ" to "밀크티 베이지",      // was: 우유 차 베이지입니다
        "ハンサムショート" to "핸섬 숏",                // was: 잘 생긴 짧은
        "ハイライトカラー" to "하이라이트 컬러",        // was: 색상을 강조 표시하십시오
        "ミセス" to "미시 스타일",                      // was: 부인
        "件" to "건",                                   // was: 문제
        "特集とは" to "특집이란",                       // was: 특별한 기능
        "ヘアスタイル特集から探す" to "헤어스타일 특집으로 찾기",
        "ブリーチなしカラー" to "탈색 없는 염색",       // was: 표백이없는 색상
        "伸ばしかけヘア" to "기르는 중인 헤어",         // was: 머리를 스트레칭
        "髪質改善" to "모발 개선",
        "小顔ヘア" to "얼굴 작아 보이는 헤어",          // was: 작은 얼굴 머리
        "レディースヘア" to "여성 헤어",                // was: 여자의 머리카락
        "メンズに切り替え" to "남성으로 전환",          // was: 남자들로 전환하십시오
        "マガジン" to "매거진",                         // was: 잡지
        "エリア・キーワードから探す" to "지역·키워드로 찾기",
        "長さ・ヘアスタイルから探す" to "길이·헤어스타일로 찾기",
        "髪型・カラーなど" to "헤어스타일·컬러 등",
        "を条件に追加" to "을(를) 조건에 추가",
        "年代" to "연령대",                             // was: 나이
        "エリア・駅 指定なし" to "지역·역 지정 안 함",
        "PICK UP ヘアスタイル動画" to "PICK UP 헤어스타일 동영상",

        // --- further ML Kit failures caught on this device, from translations.jsonl ---
        "キーワードから探す" to "키워드로 찾기",       // was: 키워드로 검색하십시오
        "COIN+とは" to "COIN+란",                     // was: 동전 +에 대하여
        "例）パリジェンヌ" to "예) 파리지엔느",        // was: 예) 파리
        "例）リンパマッサージ" to "예) 림프 마사지",   // was: 예) 림프계
        "サロンからの\nメッセージ" to "살롱에서 온 메시지",
        "利用開始" to "이용 시작",

        // --- UI labels read off real screens ---
        "ヘア" to "헤어",
        "ネイル" to "네일",
        "まつげ" to "속눈썹",
        "リラク" to "릴랙스",
        "エステ" to "에스테틱",
        "検索" to "검색",
        "指定なし" to "지정 안 함",
        "日時" to "일시",
        "メニューなど" to "메뉴 등",
        "エリア" to "지역",
        "キーワード" to "키워드",
        "ヘアスタイル" to "헤어스타일",
        "スタイル・デザイン" to "스타일·디자인",
        "動画" to "동영상",
        "条件を指定して探す" to "조건을 지정해 찾기",
        "条件変更" to "조건 변경",
        "エリア・駅・現在地" to "지역·역·현재 위치",
        "長さ" to "길이",
        "スタイル検索" to "스타일 검색",
        "サロン検索" to "살롱 검색",
        "ブックマーク" to "북마크",
        "予約履歴" to "예약 내역",
        "マイページ" to "마이페이지",
        "ログイン" to "로그인",
        "ポイントがサロン予約でたまる・つかえる！" to "포인트가 살롱 예약으로 적립·사용 가능!",

        // --- katakana loanwords, read off translations.jsonl ---
        // Every one of these was on a real screen. Katakana-only strings the glossary
        // does not know are now left untranslated (see isKatakanaOnly), so this list is
        // what keeps the terms we have actually seen from going quiet.
        "ツインテール" to "트윈테일",        // was: 쌍둥이 꼬리
        "サイドポニー" to "사이드 포니",     // was: 측면 조랑말
        "フレンチバング" to "프렌치 뱅",     // was: 프랑스 쾅
        "シースルーバング" to "시스루 뱅",   // was: 슈즈 루 팡
        "ハッシュカット" to "해시컷",        // was: 해시
        "キッズカット" to "키즈컷",          // was: 아이들이 자른다
        "レイヤーカット" to "레이어컷",      // was: 레이어
        "ハイトーンカラー" to "하이톤 컬러", // was: 하이 톤 색상
        "ウェーブヘア" to "웨이브 헤어",     // was: 웨이브 머리
        "ホーム" to "홈",                    // was: 집
        // These the engine already got right; listed so the skip rule does not lose them.
        "プレミアム" to "프리미엄",
        "ジャンル" to "장르",
        "サロン" to "살롱",
        "スタイリスト" to "스타일리스트",
        "ネイルデザイン" to "네일 디자인",
    )

    /**
     * NOT verified against a real screen. Plausible beauty-domain terms added to cover
     * the menu / booking / salon-info screens. Treat every hit in the log as something
     * to check, not something known good.
     */
    private val GUESSED: Map<String, String> = mapOf(
        // treatments
        "カット" to "커트",
        "カラー" to "컬러",
        "パーマ" to "펌",
        "縮毛矯正" to "매직 스트레이트",
        "トリートメント" to "트리트먼트",
        "ヘッドスパ" to "헤드 스파",
        "シャンプー" to "샴푸",
        "ブロー" to "드라이",
        "ヘアセット" to "헤어 세트",
        "エクステ" to "익스텐션",
        "白髪染め" to "새치 염색",
        "インナーカラー" to "이너 컬러",
        "グラデーションカラー" to "그라데이션 컬러",
        "デジタルパーマ" to "디지털 펌",
        "ストレートパーマ" to "스트레이트 펌",
        "前髪カット" to "앞머리 커트",
        "メンズカット" to "남성 커트",
        "ブリーチ" to "탈색",
        "ヘアカラー" to "헤어 컬러",
        "髪質" to "모발 상태",

        // booking
        "空席状況" to "예약 가능 현황",
        "指名料" to "지명료",
        "予約" to "예약",
        "ネット予約" to "온라인 예약",
        "当日予約" to "당일 예약",
        "予約する" to "예약하기",
        "クーポン" to "쿠폰",
        "新規" to "신규",
        "再来" to "재방문",
        "所要時間" to "소요 시간",
        "キャンセル" to "취소",
        "空き状況" to "빈자리 현황",
        "来店" to "방문",
        "人数" to "인원",
        "希望日" to "희망일",

        // salon info
        "営業時間" to "영업시간",
        "定休日" to "정기 휴일",
        "アクセス" to "오시는 길",
        "住所" to "주소",
        "電話番号" to "전화번호",
        "スタッフ" to "스태프",
        "口コミ" to "리뷰",
        "地図" to "지도",
        "料金" to "요금",
        "メニュー" to "메뉴",
        "店舗情報" to "매장 정보",
        "最寄駅" to "가까운 역",
        "駐車場" to "주차장",
        "支払方法" to "결제 방법",
        "設備" to "시설",

        // generic UI
        "もっと見る" to "더 보기",
        "絞り込み" to "필터",
        "並び替え" to "정렬",
        "人気順" to "인기순",
        "おすすめ" to "추천",
        "新着" to "신착",
        "すべて" to "전체",
        "閉じる" to "닫기",
        "戻る" to "뒤로",
        "次へ" to "다음",
        "決定" to "확인",
        "クリア" to "초기화",
        "検索結果" to "검색 결과",
        "該当なし" to "해당 없음",
        "お気に入り" to "즐겨찾기",
        "ランキング" to "랭킹",
        "性別" to "성별",
        "女性" to "여성",
        "男性" to "남성",

        // more styles
        "ボブ" to "보브",
        "ミディアムボブ" to "미디엄 보브",
        "ウルフカット" to "울프 커트",
        "レイヤー" to "레이어",
        "ストレート" to "스트레이트",
        "巻き髪" to "웨이브 헤어",
        "アップスタイル" to "업스타일",
        "編み込み" to "땋은 머리",
        "ヘアアレンジ" to "헤어 스타일링",
        "前髪あり" to "앞머리 있음",
        "前髪なし" to "앞머리 없음",
        "黒髪" to "흑발",
        "アッシュ" to "애쉬",
        "ベージュ" to "베이지",
        "グレージュ" to "그레이지",
    )

    data class Hit(val result: String, val guessed: Boolean)

    fun lookup(text: String): Hit? {
        VERIFIED[text]?.let { return Hit(it, guessed = false) }
        GUESSED[text]?.let { return Hit(it, guessed = true) }
        // A label the app wrapped arrives with the break in it — "ネイル\nデザイン" is
        // the same term as ネイルデザイン, and where it wraps depends on the column
        // width, so it cannot be listed above. Japanese sets no space at a wrap, so the
        // break is removed rather than turned into one.
        if (text.any { it.isWhitespace() }) {
            val joined = text.filterNot { it.isWhitespace() }
            VERIFIED[joined]?.let { return Hit(it, guessed = false) }
            GUESSED[joined]?.let { return Hit(it, guessed = true) }
        }
        return null
    }

    val verifiedCount get() = VERIFIED.size
    val guessedCount get() = GUESSED.size
}
