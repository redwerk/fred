function toggle(eventData) {
    var options = eventData.data;
    var hide;
    var show;

    if ($(this).is(":checked")) {
        hide = options.unchecked;
        show = options.checked;
    } else {
        hide = options.checked;
        show = options.unchecked;
    }

    $(hide).slideUp();
    $(show).slideDown();
}

/* Knowing someone prompts asking whether strangers should be connected to. */
$('#knowSomeone').change({
    'checked': '#checkDarknet',
    'unchecked': '#noDarknet'
}, toggle).change();

$('#haveMonthlyLimit').change({
    'checked': '#monthlyLimitChecked',
    'unchecked': '#monthlyLimitUnchecked'
}, toggle).change();

$('#setPassword').change({
    'checked': '#givePassword',
    'unchecked': ''
}, toggle).change();

$('#hasFDE').change({
    'checked': '#encryptionDisabled',
    'unchecked': ''
}, toggle).change();
