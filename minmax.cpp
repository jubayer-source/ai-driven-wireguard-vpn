#include <bits/stdc++.h>
using namespace std;

void StraighMinMax(int a[], int l, int h, int *max, int *min)
{
    int max1;
    int min1;

    if (l == h)
    {
        *max = *min = a[l];
    }
    else if (l == h - 1)
    {
        if (a[l] < a[h])
        {
            *max = a[h];
            *min = a[l];
        }
        else
        {
            *min = a[h];
            *max = a[l];
        }
    }
    else
    {
        int mid = (l + h) / 2;

        StraighMinMax(a, l, mid, max, min);
        StraighMinMax(a, mid + 1, h, &max1, &min1);

        if (*max < max1)
            *max = max1;

        if (*min > min1)
            *min = min1;
    }
}

int main()
{
    int a[] = {12, 45, 7, 89, 23, 56, 1, 99};

    int max;
    int min;

    StraighMinMax(a, 0, 7, &max, &min);

    cout << max << endl;
    cout << min << endl;

    return 0;
}
