#include "fft.h"

int main() {
    signalsmith::linear::RealFFT<float> fft(16);
    return fft.size() == 16 ? 0 : 1;
}
