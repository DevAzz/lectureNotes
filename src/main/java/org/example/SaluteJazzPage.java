package org.example;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.util.regex.Pattern;

final class SaluteJazzPage {
    enum State {
        WAITING_USER, AUTHENTICATING, PREJOIN, WAITING_ADMISSION, IN_CALL, RECONNECTING, DENIED, ENDED, CLOSED;

        boolean canStartManually() {
            return this == WAITING_USER || this == IN_CALL;
        }
    }

    private static final Pattern DENIED = label(
            "Организатор исключил вас из зала ожидания|Вам отказано в доступе");
    private static final Pattern WAITING = label(
            "Пожалуйста, ожидайте[.!]? Организатор скоро вас впустит|Организатор скоро вас впустит");
    private static final Pattern ENDED = label("Встреча завершена|Организатор завершил встречу");
    private static final Pattern RECONNECTING = label("Переподключение|Восстанавливаем соединение");
    private static final Pattern PREJOIN = label("Присоединиться|Подключиться|Продолжить в браузере");
    private static final Pattern AUTH = label("Вход в аккаунт|Подтвердить вход|Подтвердите вход|Подтвердите, что вы не робот");
    private static final Pattern SIGN_IN = label("Войти");
    private static final Pattern LEAVE = label("Покинуть встречу|Выйти из встречи|Завершить звонок");
    private static final Pattern PARTICIPANTS = label("Участники|Список участников");
    private static final Pattern CALL_PATH = Pattern.compile("^/calls/[^/]+/?$");
    private static final Pattern CALL_TITLE = label("Видеовстреча");
    private static final Pattern CALL_TIMER = Pattern.compile("^\\d{2,}:[0-5]\\d(?::[0-5]\\d)?$");

    static State observe(Page page, URI invitation) {
        String address = page.url();
        URI url;
        try { url = URI.create(address); }
        catch (IllegalArgumentException error) { return State.AUTHENTICATING; }
        if (!meetingOrigin(url, invitation)) return State.AUTHENTICATING;
        State state = onMeetingOrigin(page, url);
        return address.equals(page.url()) ? state : State.AUTHENTICATING;
    }

    private static State onMeetingOrigin(Page page, URI url) {
        if (visibleNotice(page, DENIED)) return State.DENIED;
        if (visibleNotice(page, ENDED)) return State.ENDED;
        if (visibleNotice(page, RECONNECTING)) return State.RECONNECTING;
        if (visibleNotice(page, WAITING)) return State.WAITING_ADMISSION;
        if (page.locator("input[type=password]:visible, iframe[src*='captcha' i]:visible").count() > 0) {
            return State.AUTHENTICATING;
        }
        if (button(page, LEAVE) && button(page, PARTICIPANTS)) return State.IN_CALL;
        if (button(page, PREJOIN)) return State.PREJOIN;
        if (visibleNotice(page, AUTH) || button(page, AUTH)) return State.AUTHENTICATING;
        if (visible(page.locator("input[type=email]")) && button(page, SIGN_IN)) return State.AUTHENTICATING;
        if (CALL_PATH.matcher(url.getRawPath()).matches()
                && visible(page.getByText(CALL_TITLE)) && visible(page.getByText(CALL_TIMER))) {
            return State.IN_CALL;
        }
        return State.WAITING_USER;
    }

    private static boolean meetingOrigin(URI url, URI invitation) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) return false;
        return invitation.getHost().equalsIgnoreCase(url.getHost())
                || ("jazz.sber.ru".equalsIgnoreCase(invitation.getHost())
                    && "salutejazz.ru".equalsIgnoreCase(url.getHost()));
    }

    private static boolean button(Page page, Pattern name) {
        var locator = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name))
                .filter(new Locator.FilterOptions().setVisible(true));
        return locator.count() == 1 && locator.isEnabled();
    }

    private static boolean visibleNotice(Page page, Pattern name) {
        var region = page.locator("h1, h2, h3, [role=heading], [role=alert], [role=status], [role=alertdialog], [role=dialog]");
        var notice = region.and(page.getByText(name)).or(region.getByText(name));
        return visible(notice);
    }

    private static boolean visible(Locator locator) {
        return locator.filter(new Locator.FilterOptions().setVisible(true)).count() > 0;
    }

    private static Pattern label(String names) {
        return Pattern.compile("^(?:" + names + ")[.!…]*$", Pattern.CASE_INSENSITIVE);
    }
}
