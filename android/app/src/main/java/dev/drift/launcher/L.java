package dev.drift.launcher;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal in-app localization. The upstream project hard-codes its UI strings in Java, so instead
 * of a large refactor to resources this keeps every English literal in place and swaps it for a
 * Russian one at the few places where text reaches a widget. Falls back to English for anything
 * missing, including all strings produced by the Rust core.
 */
final class L {
    private L() {}

    /** Read on every call so a system language change is picked up without a restart. */
    static boolean isRu() {
        return Locale.getDefault().getLanguage().equals("ru");
    }

    private static final Map<String, String> M = new HashMap<>();

    static {
        // Settings screen
        put("Drift settings", "Настройки Drift");
        put("Changes apply when you go back to the home screen.",
            "Изменения применяются при возврате на рабочий стол.");
        put("Theme", "Тема");
        put("Presets", "Готовые темы");
        put("Graphite", "Графит");
        put("Midnight", "Полночь");
        put("Forest", "Лес");
        put("Warm", "Тёплая");
        put("Light", "Светлая");
        put("Rose", "Розовая");
        put("Background", "Фон");
        put("Colors", "Цвета");
        put("Image", "Картинка");
        put("Background color", "Цвет фона");
        put("Dots color", "Цвет точек");
        put("Picture", "Изображение");
        put("Tap to choose another", "Нажмите, чтобы выбрать другое");
        put("Not chosen", "Не выбрано");
        put("Static", "Статичный");
        put("Infinite", "Бесконечный");
        put("Infinite: the picture repeats forever and is attached to the canvas. It moves and zooms with the cards.",
            "Бесконечный: картинка повторяется и привязана к холсту. Двигается и масштабируется вместе с карточками.");
        put("Static: like ordinary wallpaper. It stays where it is while the canvas moves.",
            "Статичный: как обычные обои. Остаётся на месте, пока холст двигается.");
        put("Remove picture", "Удалить изображение");
        put("Go back to plain colors", "Вернуться к обычным цветам");
        put("Interface", "Интерфейс");
        put("Interface color", "Цвет интерфейса");
        put("One color for cards, dock, buttons, text and the app list.",
            "Один цвет для карточек, дока, кнопок, текста и списка приложений.");
        put("Reset appearance", "Сбросить оформление");
        put("Default colors, no picture", "Цвета по умолчанию, без картинки");
        put("Dock apps", "Приложения в доке");
        put("How many slots the dock shows", "Сколько слотов показывает док");
        put("Folders in the dock", "Папки в доке");
        put("How to group apps in the dock", "Как объединять приложения в доке");
        put("Reset colors and remove the background picture?",
            "Сбросить цвета и удалить фоновое изображение?");
        put("Reset", "Сбросить");
        put("Quick actions", "Быстрые действия");
        put("Previews", "Превью приложений");
        put("Accessibility service", "Служба специальных возможностей");
        put("On", "Вкл");
        put("Off", "Выкл");
        put("Battery", "Батарея");
        put("Keep the launcher alive", "Чтобы лаунчер не выгружался");
        put("Unrestricted", "Без ограничений");
        put("Restricted", "С ограничениями");
        put("Diagnostics", "Диагностика");
        put("Check system navigation", "Проверка системной навигации");
        put("App info", "Сведения о приложении");
        put("Permissions, battery, uninstall", "Разрешения, батарея, удаление");
        put("Version", "Версия");
        put("License", "Лицензия");
        put("Gestures", "Жесты");
        put("Tap, double tap, drag, pinch", "Тап, двойной тап, перетаскивание, пинч");
        put("Close", "Закрыть");
        put("Single tap — select a card.\n"
                + "Double tap — open the app.\n"
                + "Long press — select and raise the card.\n"
                + "Drag a card — move it; bring it close to another card to form a group.\n"
                + "Drag empty space — pan the canvas.\n"
                + "Pinch — zoom the canvas; pinch a selected card to resize it.\n"
                + "Double tap empty space — go home and back.\n"
                + "Three-finger swipe — jump to the nearest card.\n"
                + "Back — deselect the card or close the app list.",
            "Один тап — выделить карточку.\n"
                + "Двойной тап — открыть приложение.\n"
                + "Долгий тап — выделить и поднять карточку.\n"
                + "Перетаскивание карточки — двигать; поднесите к другой, чтобы создать группу.\n"
                + "Перетаскивание пустого места — панорама холста.\n"
                + "Пинч — зум холста; пинч на выделенной карточке — изменить её размер.\n"
                + "Двойной тап по пустому месту — домой и обратно.\n"
                + "Свайп тремя пальцами — прыжок к ближайшей карточке.\n"
                + "Назад — снять выделение или закрыть список приложений.");
        put("Couldn't open settings", "Не удалось открыть настройки");
        put("About", "О приложении");
        put("No browser found", "Браузер не найден");
        put("No app to pick a picture", "Нет приложения для выбора картинки");
        put("Loading picture...", "Загрузка картинки…");
        put("Couldn't read that picture", "Не удалось прочитать картинку");

        // App list / drawer
        put("Search apps", "Поиск приложений");
        put("Drift settings  \u203A", "Настройки Drift  \u203A");
        put("Choose an app for dock slot ", "Выберите приложение для слота дока ");
        put("Add app to folder", "Добавить приложение в папку");

        // Colour picker
        put("Default", "По умолчанию");

        // Card toolbar
        put("Center", "Центр");
        put("Group", "Группа");
        put("Pin", "В док");
        put("Remove", "Удалить");
        put("Preview", "Превью");

        // Home screen / dock
        put("Workspace restored from backup", "Рабочий стол восстановлен из резервной копии");
        put("Saved workspace was unreadable; started empty (old file kept)",
            "Сохранённый рабочий стол не читается; начат пустой (старый файл сохранён)");
        put("Replace app", "Заменить приложение");
        put("Remove from dock", "Убрать из дока");
        put("Dock slot ", "Слот дока ");
        put("Add app", "Добавить приложение");
        put("Create folder", "Создать папку");
        put("Rename folder", "Переименовать папку");
        put("Folder name", "Название папки");
        put("Folder", "Папка");
        put("Add", "Добавить");
        put("Take ", "Убрать ");
        put(" out of the folder?", " из папки?");
        put("The apps and folders in the removed slots will be lost. Continue?",
            "Приложения и папки в удаляемых слотах пропадут. Продолжить?");
        put("Drift crashed. Details (select and copy, or send to the developer):",
            "Drift упал. Подробности (выделите и скопируйте или отправьте разработчику):");
        put("Clear report and retry", "Очистить отчёт и повторить");

        // Diagnostics screen
        put("Drift: system navigation check\nRun this while Drift is the selected home app.",
            "Drift: проверка системной навигации\nЗапустите, когда Drift выбран домашним приложением.");
        put("Collecting...", "Собираю…");
        put("Copy", "Копировать");
        put("Copied", "Скопировано");
        put("Share", "Поделиться");
        put("Send report", "Отправить отчёт");

        // Toasts emitted by the Rust core
        put("Nothing on the canvas yet", "На холсте пока ничего нет");
        put("No other recent cards", "Других недавних карточек нет");
        put("Already in the dock", "Уже в доке");
        put("Dock is full: long-press a slot to replace it",
            "Док заполнен: долгий тап по слоту, чтобы заменить");
        put("This app is not installed. Long-press the slot to replace it",
            "Приложение не установлено. Долгий тап по слоту, чтобы заменить");
    }

    private static void put(String en, String ru) {
        M.put(en, ru);
    }

    /** Translate one of the known English literals; unknown strings stay as they are. */
    static String t(String en) {
        if (!isRu() || en == null) return en;
        String ru = M.get(en);
        return ru != null ? ru : en;
    }

    static String t(String en, String ru) {
        return isRu() ? ru : en;
    }

    /** Translate a toast that may come from Java or from the Rust core. */
    static String toast(String s) {
        if (!isRu() || s == null) return s;
        String ru = M.get(s);
        if (ru != null) return ru;
        String tail = " is not installed. Select it and tap remove to clear the card";
        if (s.endsWith(tail)) {
            return "Приложение «" + s.substring(0, s.length() - tail.length())
                + "» не установлено. Выделите карточку и нажмите «Удалить».";
        }
        return s;
    }

    /** Search in the drawer should find the settings row in either language. */
    static boolean settingsMatches(String query) {
        if (query == null || query.isEmpty()) return true;
        if ("drift settings".contains(query)) return true;
        return isRu() && "настройки drift".contains(query);
    }
}
