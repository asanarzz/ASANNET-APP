package com.kafinet.asannet

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView

/**
 * دیالوگ انتخاب تاریخ تولد به‌صورت شمسی، بدون نیاز به تایپ دستی.
 * کاربر با سه چرخ (روز، ماه، سال) تاریخ را انتخاب می‌کند.
 */
class PersianDatePickerDialog(
    context: Context,
    private val initialYear: Int = 1375,
    private val initialMonth: Int = 1,
    private val initialDay: Int = 1,
    private val onDateSelected: (year: Int, month: Int, day: Int) -> Unit
) : Dialog(context) {

    private lateinit var pickerDay: NumberPicker
    private lateinit var pickerMonth: NumberPicker
    private lateinit var pickerYear: NumberPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_persian_date_picker, null)
        setContentView(view)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        pickerDay = view.findViewById(R.id.picker_day)
        pickerMonth = view.findViewById(R.id.picker_month)
        pickerYear = view.findViewById(R.id.picker_year)

        val minYear = 1300
        val maxYear = 1410

        pickerYear.minValue = minYear
        pickerYear.maxValue = maxYear
        pickerYear.value = initialYear.coerceIn(minYear, maxYear)
        pickerYear.wrapSelectorWheel = false

        pickerMonth.minValue = 1
        pickerMonth.maxValue = 12
        pickerMonth.displayedValues = PersianDateUtils.monthNames
        pickerMonth.value = initialMonth.coerceIn(1, 12)
        pickerMonth.wrapSelectorWheel = false

        val initialMaxDay = PersianDateUtils.daysInMonth(pickerYear.value, pickerMonth.value)
        pickerDay.minValue = 1
        pickerDay.maxValue = initialMaxDay
        pickerDay.value = initialDay.coerceIn(1, initialMaxDay)
        pickerDay.wrapSelectorWheel = false

        val updateDayRange = {
            val maxDay = PersianDateUtils.daysInMonth(pickerYear.value, pickerMonth.value)
            if (pickerDay.value > maxDay) pickerDay.value = maxDay
            pickerDay.maxValue = maxDay
        }
        pickerMonth.setOnValueChangedListener { _, _, _ -> updateDayRange() }
        pickerYear.setOnValueChangedListener { _, _, _ -> updateDayRange() }

        view.findViewById<TextView>(R.id.btn_date_cancel).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.btn_date_confirm).setOnClickListener {
            onDateSelected(pickerYear.value, pickerMonth.value, pickerDay.value)
            dismiss()
        }
    }
}
